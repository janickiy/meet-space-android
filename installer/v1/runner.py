#!/usr/bin/env python3
"""MeetSpace source installer v1. JSON control protocol, no credentials in output.

The SSH bootstrap supplies trusted source/key/manifest bytes from the signed APK.
After apply the same files live in a root-owned directory. Never executes config.
"""
import base64
import copy
import contextlib
import datetime
import fcntl
import hashlib
import http.client
import ipaddress
import json
import os
import pathlib
import re
import secrets
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

VERSION = "1.0.0"
ROOT = pathlib.Path("/opt/meetspace")
PROJECT = "meetspace"
MAX_JSON = 256 * 1024
TERMINAL = {"completed", "cancelled", "failed", "needs_recovery"}
PHASES = ("docker", "source", "images", "configuration", "dependencies", "backup", "migrations", "https", "services", "readiness")
APP_SERVICES = ("api", "media-worker", "worker", "product-worker", "live-worker", "frontend", "minio")
PORTS = {"tcp": [80, 443, 3478, 5349, 50010], "udp": [3478, 50010] + list(range(49160, 49260))}
SOURCE_POLICY = "github-latest-stable-v1"
PREBUILT_POLICY = "github-prebuilt-v1"
PREBUILT_SERVICES = (*APP_SERVICES, "postgres", "redis", "rabbitmq", "coturn", "proxy", "prometheus")
MAX_IMAGE_ARCHIVE = 2 * 1024 ** 3
UBUNTU_SUITES = {"22.04": "jammy", "24.04": "noble"}
SUPPORTED_ARCHES = {"amd64", "arm64"}
DOCKER_PACKAGES = {"containerd.io", "docker-ce", "docker-ce-cli", "docker-buildx-plugin", "docker-compose-plugin"}
RESOURCE_PROFILES = {
    "standard": {"cpus": 4, "memoryKiB": 7 * 1024 ** 2, "freshDiskBytes": 30 * 1024 ** 3},
    "compact-test": {"cpus": 2, "memoryKiB": 3 * 1024 ** 2, "freshDiskBytes": 18 * 1024 ** 3},
}
REPOSITORY = "janickiy/meet-space"
GITHUB_API = "https://api.github.com/repos/" + REPOSITORY
SAFE_ENV = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "HOME": "/root", "DEBIAN_FRONTEND": "noninteractive", "GIT_TERMINAL_PROMPT": "0"}
DOCKER_PLUGINS = (("installCompose", "docker-compose-plugin", "install-compose", "COMPOSE_REQUIRED"),
                  ("installBuildx", "docker-buildx-plugin", "install-buildx", "BUILDX_REQUIRED"))


class InstallError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def fail(code):
    raise InstallError(code)


def build_timestamp_utc(value):
    """Keep the signed instant intact; Docker build metadata requires UTC seconds."""
    if not isinstance(value, str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])", value
    ):
        fail("RELEASE_INVALID")
    try:
        stamp = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
        return stamp.astimezone(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except (ValueError, OverflowError):
        fail("RELEASE_INVALID")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(raw, maximum=MAX_JSON, *, expected_type=dict):
    if len(raw) > maximum:
        fail("INVALID_REQUEST")
    def pairs(items):
        out = {}
        for key, value in items:
            if key in out:
                fail("INVALID_REQUEST")
            out[key] = value
        return out
    try:
        value = json.loads(raw, object_pairs_hook=pairs)
        if not isinstance(value, expected_type):
            fail("INVALID_REQUEST")
        return value
    except (ValueError, TypeError, UnicodeError):
        fail("INVALID_REQUEST")


def run(args, *, timeout=30, capture=False, data=None, env=None, cwd=None, code="COMMAND_FAILED", pass_fds=()):
    """Never includes argv, output, environment or subprocess exceptions in errors."""
    try:
        result = subprocess.run(args, input=data, stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=timeout, check=False,
                                env=env or SAFE_ENV, cwd=cwd, pass_fds=pass_fds)
        if result.returncode != 0:
            fail(code)
        if capture:
            if len(result.stdout) > MAX_JSON:
                fail("RESPONSE_LIMIT")
            return result.stdout
        return b""
    except (OSError, subprocess.TimeoutExpired):
        fail(code)


def no_symlink(path, require_root=True):
    path = pathlib.Path(path)
    for part in [path, *path.parents]:
        if part.exists() or part.is_symlink():
            info = part.lstat()
            if stat.S_ISLNK(info.st_mode) or (require_root and info.st_uid != 0):
                fail("UNSAFE_PATH")
            if part != pathlib.Path("/") and info.st_mode & 0o022:
                fail("UNSAFE_PATH")


def write_atomic(path, data, mode=0o600):
    path = pathlib.Path(path)
    no_symlink(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    no_symlink(path.parent)
    temporary = path.with_name("." + path.name + "." + secrets.token_hex(8))
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def read_json(path):
    no_symlink(path)
    try:
        return strict_json(pathlib.Path(path).read_bytes())
    except OSError:
        fail("STATE_UNAVAILABLE")


def verify_bundle(source, pem, raw, signature, docker_key):
    # Temporary files are bounded and deleted; no installer state is written by plan.
    if len(source) > 256 * 1024 or len(pem) > 8192 or len(signature) > 1024:
        fail("RELEASE_INVALID")
    with tempfile.TemporaryDirectory(prefix="meetspace-verify-") as directory:
        key_path = pathlib.Path(directory) / "publisher.pem"
        signature_path = pathlib.Path(directory) / "signature"
        key_path.write_bytes(pem)
        signature_path.write_bytes(signature)
        run(["openssl", "dgst", "-sha256", "-verify", str(key_path), "-signature", str(signature_path)],
            data=raw, code="RELEASE_SIGNATURE")
    manifest = strict_json(raw)
    if manifest.get("schema") != 1 or manifest.get("installerVersion") != VERSION:
        fail("RELEASE_INVALID")
    if manifest.get("runnerSha256") != digest(source) or manifest.get("dockerKeySha256") != digest(docker_key):
        fail("RELEASE_CHECKSUM")
    if not re.fullmatch(r"[a-f0-9]{40}", manifest.get("commit", "")):
        fail("RELEASE_INVALID")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", manifest.get("version", "")):
        fail("RELEASE_INVALID")
    expected_url = "https://codeload.github.com/janickiy/meet-space/tar.gz/" + manifest["commit"]
    if manifest.get("sourceUrl") != expected_url or not re.fullmatch(r"[a-f0-9]{64}", manifest.get("sourceSha256", "")):
        fail("RELEASE_INVALID")
    if manifest.get("profile") not in ("source-v1", "integration-v1"):
        fail("RELEASE_INVALID")
    for image in [*manifest.get("baseImages", {}).values(), *manifest.get("images", {}).values()]:
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9/_.:-]*@sha256:[a-f0-9]{64}", image):
            fail("RELEASE_INVALID")
    if set(manifest.get("dockerfiles", {})) != set(APP_SERVICES):
        fail("RELEASE_INVALID")
    validate_docker_manifest(manifest)
    return manifest


def load_bundle():
    if "BOOTSTRAP_BUNDLE" in globals():
        bundle = BOOTSTRAP_BUNDLE
    else:
        directory = pathlib.Path(__file__).resolve().parent
        bundle = {name: (directory / name).read_bytes() for name in ("runner.py", "trust-root.pem", "release.json", "release.sig", "docker.asc")}
    if set(bundle) != {"runner.py", "trust-root.pem", "release.json", "release.sig", "docker.asc"}:
        fail("RELEASE_INVALID")
    manifest = verify_bundle(bundle["runner.py"], bundle["trust-root.pem"], bundle["release.json"], bundle["release.sig"], bundle["docker.asc"])
    return manifest, bundle


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fail("SOURCE_LOOKUP_FAILED")


def github_json(path, *, missing_ok=False, expected_type=dict):
    """Anonymous public metadata only; a missing release is the sole fallback condition."""
    request = urllib.request.Request(GITHUB_API + path, headers={
        "User-Agent": "MeetSpace-Installer/1", "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=15) as response:
            return strict_json(response.read(MAX_JSON + 1), expected_type=expected_type)
    except InstallError as error:
        if error.code == "INVALID_REQUEST":
            fail("SOURCE_INVALID")
        raise
    except urllib.error.HTTPError as error:
        if error.code == 404 and missing_ok:
            return None
        fail("SOURCE_RATE_LIMIT" if error.code in (403, 429) else "SOURCE_LOOKUP_FAILED")
    except (OSError, urllib.error.URLError):
        fail("SOURCE_LOOKUP_FAILED")


def source_url(commit):
    return "https://codeload.github.com/" + REPOSITORY + "/tar.gz/" + commit


def source_snapshot(commit, recipe, *, building=True):
    """Hash the commit archive and required inputs without extracting or executing it."""
    with tempfile.TemporaryDirectory(prefix="meetspace-source-") as temporary:
        archive = pathlib.Path(temporary) / "source.tar.gz"
        total, checksum, deadline = 0, hashlib.sha256(), time.monotonic() + 90
        try:
            request = urllib.request.Request(source_url(commit), headers={"User-Agent": "MeetSpace-Installer/1"})
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response, archive.open("wb") as output:
                while True:
                    chunk = response.read(128 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > 100 * 1024 * 1024 or time.monotonic() > deadline:
                        fail("DOWNLOAD_LIMIT")
                    checksum.update(chunk)
                    output.write(chunk)
            contents = {}
            prefix = "meet-space-" + commit + "/"
            with tarfile.open(archive, "r:gz") as source:
                count, expanded, seen = 0, 0, set()
                for member in source:
                    count += 1
                    expanded += member.size
                    path = pathlib.PurePosixPath(member.name)
                    in_root = member.name.startswith(prefix) or (member.isdir() and member.name == prefix[:-1])
                    if count > 30000 or expanded > 256 * 1024 * 1024 or path.is_absolute() or ".." in path.parts or not in_root or not (member.isfile() or member.isdir()) or member.name in seen:
                        fail("SOURCE_INVALID")
                    seen.add(member.name)
                    relative = member.name[len(prefix):]
                    if relative in recipe["sourceFiles"] and member.isfile():
                        if member.size > 2 * 1024 * 1024:
                            fail("SOURCE_INCOMPATIBLE")
                        with source.extractfile(member) as stream:
                            contents[relative] = stream.read()
            if set(contents) != set(recipe["sourceFiles"]):
                fail("SOURCE_INCOMPATIBLE")
            if building:
                for dockerfile in recipe["dockerfiles"].values():
                    pinned_dockerfile(contents[dockerfile["path"]].decode("utf-8"), recipe["baseImages"])
            return checksum.hexdigest(), {path: digest(data) for path, data in contents.items()}
        except (OSError, urllib.error.URLError, tarfile.TarError, UnicodeError):
            fail("SOURCE_LOOKUP_FAILED")


def resolve_source(recipe):
    release = github_json("/releases/latest", missing_ok=True)
    if release is None:
        ref = github_json("").get("default_branch")
        channel, version = "default-branch", None
    else:
        if release.get("draft") is not False or release.get("prerelease") is not False:
            fail("SOURCE_INVALID")
        ref = release.get("tag_name")
        channel, version = "release", ref
        if not isinstance(version, str) or not re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[+-][A-Za-z0-9.-]+)?", version) or len(version) > 64:
            fail("SOURCE_INCOMPATIBLE")
    if not isinstance(ref, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/+-]{0,199}", ref):
        fail("SOURCE_INVALID")
    # List one commit from the selected ref. Unlike /commits/{ref}, this endpoint
    # excludes changed files and patches, so a large release diff stays bounded.
    # https://docs.github.com/en/rest/commits/commits#list-commits
    entries = github_json("/commits?sha=" + urllib.parse.quote(ref, safe="") + "&per_page=1", expected_type=list)
    if not isinstance(entries, list) or len(entries) != 1 or not isinstance(entries[0], dict):
        fail("SOURCE_INVALID")
    metadata = entries[0]
    commit = metadata.get("sha", "")
    details = metadata.get("commit")
    committer = details.get("committer") if isinstance(details, dict) else None
    committed_at = committer.get("date") if isinstance(committer, dict) else None
    if not isinstance(commit, str) or not re.fullmatch(r"[a-f0-9]{40}", commit) or not isinstance(committed_at, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", committed_at):
        fail("SOURCE_INVALID")
    checksum, files = source_snapshot(commit, recipe)
    lock = {"repository": REPOSITORY, "channel": channel, "ref": ref,
            "version": version or "0.0.0-source." + commit[:12], "commit": commit,
            "builtAt": committed_at, "sourceUrl": source_url(commit), "sourceSha256": checksum, "sourceFiles": files}
    locked_manifest(recipe, lock)  # Validate before a plan can be presented.
    return lock


def locked_manifest(recipe, lock):
    prebuilt = isinstance(lock, dict) and lock.get("channel") == "prebuilt" and recipe.get("prebuiltPolicy") == PREBUILT_POLICY
    if (not prebuilt and recipe.get("sourcePolicy") != SOURCE_POLICY) or not isinstance(lock, dict) or set(lock) != {"repository", "channel", "ref", "version", "commit", "builtAt", "sourceUrl", "sourceSha256", "sourceFiles"}:
        fail("PLAN_CHANGED")
    def matches(pattern, value):
        return isinstance(value, str) and re.fullmatch(pattern, value)
    if lock["repository"] != REPOSITORY or lock["channel"] not in ("release", "default-branch", "prebuilt") or not matches(r"[a-f0-9]{40}", lock["commit"]) or not matches(r"[a-f0-9]{64}", lock["sourceSha256"]) or lock["sourceUrl"] != source_url(lock["commit"]):
        fail("PLAN_CHANGED")
    if not matches(r"[A-Za-z0-9][A-Za-z0-9._/+-]{0,199}", lock["ref"]) or not matches(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[+-][A-Za-z0-9.-]+)?", lock["version"]) or len(lock["version"]) > 64 or not matches(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", lock["builtAt"]):
        fail("PLAN_CHANGED")
    if (lock["channel"] in ("release", "prebuilt") and lock["version"] != lock["ref"]) or (lock["channel"] == "default-branch" and lock["version"] != "0.0.0-source." + lock["commit"][:12]):
        fail("PLAN_CHANGED")
    files = lock["sourceFiles"]
    if not isinstance(files, dict) or set(files) != set(recipe["sourceFiles"]) or not all(matches(r"[a-f0-9]{64}", value) for value in files.values()):
        fail("PLAN_CHANGED")
    result = copy.deepcopy(recipe)
    for key in ("version", "commit", "builtAt", "sourceUrl", "sourceSha256", "sourceFiles"):
        result[key] = lock[key]
    result["backendTag"] = lock["ref"] if lock["channel"] in ("release", "prebuilt") else None
    for dockerfile in result["dockerfiles"].values():
        dockerfile["sha256"] = files[dockerfile["path"]]
    return result


def manifest_for_plan(recipe, plan):
    contents = {key: value for key, value in plan.items() if key != "hash"}
    if plan.get("hash") != digest(canonical(contents)):
        fail("PLAN_CHANGED")
    plan_resource_profile(plan)
    if "sourceLock" in plan:
        if plan.get("recipeSha256") != digest(canonical(recipe)):
            fail("PLAN_CHANGED")
        manifest = locked_manifest(recipe, plan["sourceLock"])
    else:
        manifest = recipe  # Legacy pinned plans remain readable; never resolve latest here.
    if plan.get("releaseSha256") != digest(canonical(manifest)) or plan.get("commit") != manifest["commit"] or plan.get("releaseVersion") != manifest["version"]:
        fail("PLAN_CHANGED")
    if image_mode(plan.get("options", {})) == "images":
        validate_image_lock(plan.get("imageLock"), recipe, plan.get("sourceLock"), plan.get("platform", {}).get("arch"),
                            requested=plan["options"].get("imageRelease", "latest"))
    elif "imageLock" in plan or plan.get("sourceLock", {}).get("channel") == "prebuilt":
        fail("PLAN_CHANGED")
    if "platform" in plan or "dockerPackages" in plan:
        expected = docker_packages(manifest, plan.get("platform"), legacy=True, code="PLAN_CHANGED")
        if plan.get("dockerPackages") != expected:
            fail("PLAN_CHANGED")
    return manifest


def plan_latest(options, recipe):
    validate_options(options, recipe)
    marker = managed_marker()
    if marker:
        previous = read_json(job_directory(marker["jobId"]) / "plan.json")
        manifest_for_plan(recipe, previous)
        lock = previous.get("sourceLock")
        images = previous.get("imageLock")
        if image_mode(options) != image_mode(previous["options"]):
            fail("EXISTING_INSTALLATION")
        profile = plan_resource_profile(previous)
    else:
        if image_mode(options) == "images":
            lock, images = resolve_prebuilt(recipe, platform_facts()["arch"], options.get("imageRelease", "latest"))
        else:
            lock = resolve_source(recipe) if recipe.get("sourcePolicy") == SOURCE_POLICY else None
            images = None
        profile = None
    return create_plan(options, recipe, source_lock=lock, image_lock=images, resource_profile=profile)


def image_mode(options):
    mode = options.get("imageMode", "source")
    if mode not in ("source", "images"):
        fail("INVALID_IMAGE_MODE")
    return mode


def valid_prebuilt_version(value, *, latest=False):
    return isinstance(value, str) and len(value) <= 64 and (latest and value == "latest" or
        re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", value))


class AssetRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_asset_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def check_asset_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in
            {"github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}
            or parsed.username or parsed.password or parsed.port not in (None, 443)):
        fail("PREBUILT_INVALID")


def asset_response(url):
    check_asset_url(url)
    request = urllib.request.Request(url, headers={"User-Agent": "MeetSpace-Installer/1", "Accept": "application/vnd.github+json"})
    return urllib.request.build_opener(AssetRedirect()).open(request, timeout=30)


def asset_json(url):
    try:
        with asset_response(url) as response:
            raw = response.read(4 * 1024 ** 2 + 1)
        # Duplicate JSON members are rejected as in the SSH protocol.
        value = strict_json(raw, 4 * 1024 ** 2, expected_type=(dict, list))
        return value, digest(raw)
    except InstallError as error:
        if error.code == "INVALID_REQUEST":
            fail("PREBUILT_INVALID")
        raise


def prebuilt_assets(release, arch):
    if not isinstance(release, dict) or release.get("draft") is not False or not valid_prebuilt_version(release.get("tag_name")):
        return None
    published = release.get("published_at")
    try:
        if build_timestamp_utc(published) != published:
            return None
    except InstallError:
        return None
    tag = release["tag_name"]
    names = ("prebuilt.json", "meetspace-" + tag + "-linux-" + arch + ".tar.gz")
    assets = release.get("assets")
    if not isinstance(assets, list):
        return None
    result = {}
    for name in names:
        matches = [a for a in assets if isinstance(a, dict) and a.get("name") == name]
        if len(matches) != 1:
            return None
        asset = matches[0]
        if (asset.get("state") != "uploaded" or type(asset.get("size")) is not int or
                not 0 < asset["size"] <= (4 * 1024 ** 2 if name == "prebuilt.json" else MAX_IMAGE_ARCHIVE) or
                asset.get("browser_download_url") != "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name):
            return None
        result[name] = asset
    return result


def validate_image_lock(lock, recipe, source, arch, *, requested="latest"):
    if (recipe.get("prebuiltPolicy") != PREBUILT_POLICY or not isinstance(lock, dict) or
            set(lock) != {"repository", "version", "commit", "platform", "url", "sha256", "sizeBytes", "indexSha256"} or
            not isinstance(source, dict) or source.get("channel") != "prebuilt" or arch not in SUPPORTED_ARCHES):
        fail("PLAN_CHANGED")
    version = lock["version"]
    if (not valid_prebuilt_version(requested, latest=True) or requested not in ("latest", version) or
            not valid_prebuilt_version(version) or lock["repository"] != REPOSITORY or
            lock["version"] != source.get("version") or lock["commit"] != source.get("commit") or
            not isinstance(lock["commit"], str) or not re.fullmatch(r"[a-f0-9]{40}", lock["commit"]) or
            lock["platform"] != "linux/" + arch or type(lock["sizeBytes"]) is not int or not 0 < lock["sizeBytes"] <= MAX_IMAGE_ARCHIVE or
            any(not isinstance(lock[key], str) or not re.fullmatch(r"[a-f0-9]{64}", lock[key]) for key in ("sha256", "indexSha256")) or
            lock["url"] != "https://github.com/" + REPOSITORY + "/releases/download/" + version + "/meetspace-" + version + "-linux-" + arch + ".tar.gz"):
        fail("PLAN_CHANGED")


def resolve_prebuilt(recipe, arch, requested="latest"):
    """Resolve once from published platform packages, including prereleases. No source fallback."""
    if recipe.get("prebuiltPolicy") != PREBUILT_POLICY or arch not in SUPPORTED_ARCHES or not valid_prebuilt_version(requested, latest=True):
        fail("PREBUILT_UNAVAILABLE")
    try:
        if requested == "latest":
            releases = []
            for page in range(1, 11):
                batch, _ = asset_json(GITHUB_API + "/releases?per_page=100&page=" + str(page))
                if not isinstance(batch, list) or len(batch) > 100:
                    fail("PREBUILT_INVALID")
                releases.extend(batch)
                if len(batch) < 100:
                    break
            else:
                fail("PREBUILT_UNAVAILABLE")
        else:
            release, _ = asset_json(GITHUB_API + "/releases/tags/" + urllib.parse.quote(requested, safe=""))
            releases = [release]
        candidates = [(release, assets) for release in releases if (assets := prebuilt_assets(release, arch)) is not None]
        candidates.sort(key=lambda item: (item[0]["published_at"], item[0]["tag_name"]), reverse=True)
        for release, assets in candidates:
            tag = release["tag_name"]
            try:
                index, index_hash = asset_json(assets["prebuilt.json"]["browser_download_url"])
            except urllib.error.HTTPError as error:
                if error.code == 404 and requested == "latest":
                    continue
                raise
            if (not isinstance(index, dict) or type(index.get("schemaVersion")) is not int or index["schemaVersion"] != 1 or
                    index.get("installationMode") != "prebuilt" or index.get("repository") != REPOSITORY or index.get("version") != tag or
                    not isinstance(index.get("platforms"), dict)):
                fail("PREBUILT_INVALID")
            artifact = index["platforms"].get("linux/" + arch)
            if not isinstance(artifact, dict) or set(artifact) != {"url", "sha256", "sizeBytes"}:
                fail("PREBUILT_INVALID")
            lock = dict(artifact, repository=REPOSITORY, version=tag, commit=index.get("commit"), platform="linux/" + arch, indexSha256=index_hash)
            source = {"channel": "prebuilt", "version": tag, "commit": index.get("commit")}
            validate_image_lock(lock, recipe, source, arch, requested=requested)
            metadata = assets["meetspace-" + tag + "-linux-" + arch + ".tar.gz"]
            if metadata["size"] != lock["sizeBytes"] or metadata.get("digest") not in (None, "sha256:" + lock["sha256"]):
                fail("PREBUILT_INVALID")
            commits = github_json("/commits?sha=" + lock["commit"] + "&per_page=1", expected_type=list)
            if len(commits) != 1 or commits[0].get("sha") != lock["commit"]:
                fail("PREBUILT_INVALID")
            built_at = build_timestamp_utc(commits[0].get("commit", {}).get("committer", {}).get("date"))
            checksum, files = source_snapshot(lock["commit"], recipe, building=False)
            source.update(repository=REPOSITORY, ref=tag, builtAt=built_at, sourceUrl=source_url(lock["commit"]), sourceSha256=checksum, sourceFiles=files)
            locked_manifest(recipe, source)
            return source, lock
        fail("PREBUILT_UNAVAILABLE")
    except urllib.error.HTTPError as error:
        fail("PREBUILT_UNAVAILABLE" if error.code == 404 else "SOURCE_RATE_LIMIT" if error.code in (403, 429) else "PREBUILT_DOWNLOAD_FAILED")
    except (OSError, urllib.error.URLError):
        fail("PREBUILT_DOWNLOAD_FAILED")


def file_digest(path):
    checksum = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 ** 2), b""):
            checksum.update(block)
    return checksum.hexdigest()


def download_prebuilt(lock, path, check_cancel=lambda: None):
    temporary = path.with_suffix(".partial")
    no_symlink(temporary)
    total, checksum = 0, hashlib.sha256()
    deadline = time.monotonic() + 3600
    try:
        with asset_response(lock["url"]) as stream, temporary.open("wb") as output:
            for block in iter(lambda: stream.read(1024 ** 2), b""):
                check_cancel()
                total += len(block)
                if total > lock["sizeBytes"] or time.monotonic() > deadline:
                    fail("DOWNLOAD_LIMIT")
                if disk_free() < len(block) + 2 * 1024 ** 3:
                    fail("DISK_SPACE")
                output.write(block)
                checksum.update(block)
            output.flush()
            os.fsync(output.fileno())
        if total != lock["sizeBytes"] or checksum.hexdigest() != lock["sha256"]:
            fail("RELEASE_CHECKSUM")
        os.replace(temporary, path)
    except (OSError, urllib.error.URLError):
        fail("PREBUILT_DOWNLOAD_FAILED")
    finally:
        temporary.unlink(missing_ok=True)


def extract_prebuilt(archive, destination, check_cancel=lambda: None):
    """Unpack data only, never execute the downloaded wizard or preserve archive permissions."""
    seen, total = set(), 0
    try:
        with tarfile.open(archive, "r|gz") as source:
            for member in source:
                check_cancel()
                parts = pathlib.PurePosixPath(member.name).parts
                if (not parts or parts[0] != "bundle" or member.name.startswith("/") or ".." in parts or
                        "\\" in member.name or any(ord(c) < 32 for c in member.name) or member.mode & 0o7000 or
                        not (member.isfile() or member.isdir()) or parts in seen):
                    fail("PREBUILT_INVALID")
                seen.add(parts)
                total += member.size
                if len(seen) > 50000 or total > 12 * 1024 ** 3:
                    fail("DOWNLOAD_LIMIT")
                target = destination.joinpath(*parts)
                if member.isdir():
                    target.mkdir(mode=0o700, parents=True, exist_ok=True)
                else:
                    if disk_free() < member.size + 2 * 1024 ** 3:
                        fail("DISK_SPACE")
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    with source.extractfile(member) as incoming, target.open("xb") as output:
                        shutil.copyfileobj(incoming, output, 1024 ** 2)
    except (OSError, tarfile.TarError):
        fail("PREBUILT_INVALID")
    return destination / "bundle"


def package_json(path, maximum=32 * 1024 ** 2):
    no_symlink(path)
    with path.open("rb") as stream:
        return strict_json(stream.read(maximum + 1), maximum)


def verify_prebuilt_package(bundle, lock):
    """Retain upstream archive, offline scan/SBOM and image provenance gates in Python."""
    info = package_json(bundle / "bundle.json")
    release = bundle / "release"
    if file_digest(release / "release.json") != info.get("manifestSHA256"):
        fail("RELEASE_CHECKSUM")
    manifest = package_json(release / "release.json")
    if (manifest.get("schemaVersion") != 1 or manifest.get("version") != lock["version"] or manifest.get("commit") != lock["commit"] or
            manifest.get("platform") != lock["platform"] or manifest.get("artifactMode") != "archive" or
            manifest.get("sourceDirty") is not False or manifest.get("sourceFingerprintVerified") is not True or manifest.get("securityScanned") is not True or
            manifest.get("deploymentLayout") != "hardened-shared-host" or manifest.get("objectStorageProvider") != "seaweedfs" or
            not isinstance(manifest.get("sourceSHA256"), str) or not re.fullmatch(r"[a-f0-9]{64}", manifest["sourceSHA256"]) or
            set(manifest.get("images", {})) != set(PREBUILT_SERVICES)):
        fail("PREBUILT_INVALID")
    build_timestamp_utc(manifest.get("builtAt"))
    if file_digest(release / "images.tar") != manifest.get("archiveSHA256"):
        fail("RELEASE_CHECKSUM")
    with tarfile.open(release / "images.tar", "r:") as archive:
        members = {}
        total = 0
        for member in archive:
            path = pathlib.PurePosixPath(member.name)
            total += member.size
            if (path.is_absolute() or ".." in path.parts or "\\" in member.name or not (member.isfile() or member.isdir()) or
                    member.name in members or len(members) >= 50000 or total > 12 * 1024 ** 3):
                fail("PREBUILT_INVALID")
            members[member.name] = member
        def json_member(name, expected=None, expected_type=dict):
            member = members.get(name)
            if member is None or not member.isfile() or member.size > 4 * 1024 ** 2:
                fail("PREBUILT_INVALID")
            raw = archive.extractfile(member).read()
            if expected and "sha256:" + digest(raw) != expected:
                fail("RELEASE_CHECKSUM")
            return strict_json(raw, 4 * 1024 ** 2, expected_type=expected_type)
        if "imageConfigIDs" in manifest:
            index = json_member("index.json")
            descriptors = index.get("manifests")
            if (not isinstance(descriptors, list) or len(descriptors) != len(PREBUILT_SERVICES) or
                    {item.get("digest") for item in descriptors} != set(manifest["images"].values())):
                fail("PREBUILT_INVALID")
        if "manifest.json" in members:
            saved = json_member("manifest.json", expected_type=list)
            configs = manifest.get("imageConfigIDs", manifest["images"])
            expected_configs = {"blobs/sha256/" + value[7:] if "imageConfigIDs" in manifest else value[7:] + ".json" for value in configs.values()}
            if len(saved) != len(PREBUILT_SERVICES) or {item.get("Config") for item in saved} != expected_configs:
                fail("PREBUILT_INVALID")
        elif "imageConfigIDs" not in manifest:
            fail("PREBUILT_INVALID")
        for service in PREBUILT_SERVICES:
            image = manifest["images"][service]
            config_id = manifest.get("imageConfigIDs", {}).get(service, image)
            if any(not isinstance(value, str) or not re.fullmatch(r"sha256:[a-f0-9]{64}", value) for value in (image, config_id)):
                fail("PREBUILT_INVALID")
            if "imageConfigIDs" in manifest:
                descriptor = json_member("blobs/sha256/" + image[7:], image)
                if descriptor.get("config", {}).get("digest") != config_id:
                    fail("PREBUILT_INVALID")
                config = json_member("blobs/sha256/" + config_id[7:], config_id)
            else:
                config = json_member(config_id[7:] + ".json", config_id)
            if config.get("architecture") != lock["platform"].split("/")[1] or config.get("os") != "linux":
                fail("PREBUILT_INVALID")
            verify_prebuilt_labels(service, config.get("config", {}).get("Labels", {}), manifest)
            evidence = manifest.get("securityEvidence", {}).get(service, {})
            scan, sbom = release / (service + ".scan.json"), release / (service + ".sbom.json")
            if file_digest(scan) != evidence.get("scanSHA256") or file_digest(sbom) != evidence.get("sbomSHA256"):
                fail("RELEASE_CHECKSUM")
            scan_data = package_json(scan)
            metadata = scan_data.get("Metadata", {})
            if ("SchemaVersion" not in scan_data or not isinstance(scan_data.get("Results"), list) or metadata.get("ImageID") != config_id or
                    metadata.get("ImageConfig", {}).get("architecture") != lock["platform"].split("/")[1] or
                    any(v.get("Severity") in ("HIGH", "CRITICAL") for result in scan_data["Results"] for v in (result.get("Vulnerabilities") or [])) or
                    package_json(sbom).get("bomFormat") != "CycloneDX"):
                fail("PREBUILT_SECURITY")
    return manifest


def verify_prebuilt_labels(service, labels, manifest):
    if service not in APP_SERVICES:
        return
    expected = manifest
    if service == "minio" and manifest.get("infrastructureReused") is True:
        expected = manifest.get("infrastructureSource", {})
        if (expected.get("version") != manifest.get("infrastructureVersion") or not valid_prebuilt_version(expected.get("version")) or
                not re.fullmatch(r"[a-f0-9]{40}", expected.get("commit", "")) or
                not re.fullmatch(r"[a-f0-9]{64}", expected.get("sourceSHA256", ""))):
            fail("PREBUILT_INVALID")
    if any((labels or {}).get(label) != expected.get(key) for label, key in
            (("org.opencontainers.image.version", "version"), ("org.opencontainers.image.revision", "commit"), ("io.go-recorder.source-sha256", "sourceSHA256"))):
        fail("PREBUILT_INVALID")


def plan_resource_profile(plan):
    profile = plan.get("resourceProfile", "standard")
    if not isinstance(profile, str) or profile not in RESOURCE_PROFILES:
        fail("PLAN_CHANGED")
    return profile


def validate_options(options, manifest):
    if (not isinstance(options, dict) or not {"domain", "publicIpv4"}.issubset(options)
            or set(options) - {"domain", "publicIpv4", "acmeEmail", "smtp", "imageMode", "imageRelease"}):
        fail("INVALID_REQUEST")
    domain = options["domain"]
    if not isinstance(domain, str) or len(domain) > 253 or domain != domain.lower() or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain):
        fail("INVALID_DOMAIN")
    labels = domain.split(".")
    if len(labels) < 2 or any(not 1 <= len(x) <= 63 or x.startswith("-") or x.endswith("-") for x in labels):
        fail("INVALID_DOMAIN")
    try:
        ipaddress.ip_address(domain)
        fail("INVALID_DOMAIN")
    except ValueError:
        pass
    try:
        ip = ipaddress.IPv4Address(options["publicIpv4"])
    except (ValueError, TypeError):
        fail("INVALID_PUBLIC_IP")
    if manifest["profile"] == "source-v1" and (not ip.is_global or labels[-1] in {"test", "invalid", "localhost", "local", "internal", "example"}):
        fail("PUBLIC_DOMAIN_REQUIRED")
    email = options.get("acmeEmail", "")
    if not isinstance(email, str) or (email and (len(email) > 254 or not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}", email))):
        fail("INVALID_EMAIL")
    normalized = {"domain": domain, "publicIpv4": str(ip), "acmeEmail": email}
    mode = image_mode(options)
    if "imageMode" in options:
        normalized["imageMode"] = mode
    if "imageRelease" in options:
        release = options["imageRelease"]
        if mode != "images" or not valid_prebuilt_version(release, latest=True):
            fail("INVALID_IMAGE_MODE")
        normalized["imageRelease"] = release
    if "smtp" in options:
        normalized["smtp"] = validate_smtp(options["smtp"])
    return normalized


def validate_smtp(options):
    """Validate public SMTP metadata without accepting credentials in the plan."""
    if not isinstance(options, dict) or set(options) != {"host", "port", "from", "username", "tlsMode"}:
        fail("INVALID_SMTP")
    host = options["host"]
    if not isinstance(host, str) or not host or len(host) > 253 or any(char in host for char in "/\\\r\n\t @?#%"):
        fail("INVALID_SMTP")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if not all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in host.split(".")):
            fail("INVALID_SMTP")
    port, sender, username = options["port"], options["from"], options["username"]
    if (not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535
            or options["tlsMode"] not in ("tls", "starttls")
            or not isinstance(sender, str) or len(sender) > 254
            or not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}", sender)
            or not isinstance(username, str) or not username or len(username.encode("utf-8", errors="replace")) > 512
            or any(0xD800 <= ord(char) <= 0xDFFF for char in username)
            or any(ord(char) < 32 or ord(char) == 127 for char in username)):
        fail("INVALID_SMTP")
    local, domain = sender.rsplit("@", 1)
    if (len(local) > 64 or local.startswith(".") or local.endswith(".") or ".." in local
            or not all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in domain.split("."))):
        fail("INVALID_SMTP")
    return dict(options)


def validate_smtp_password(password):
    """Accept a bounded apply-only secret; callers must never serialize it into plans."""
    if (not isinstance(password, str) or not password or len(password.encode("utf-8", errors="replace")) > 4096
            or any(0xD800 <= ord(char) <= 0xDFFF for char in password)
            or any(char in password for char in "\r\n\x00")):
        fail("SMTP_PASSWORD_REQUIRED")
    return password


def smtp_environment(options, password):
    """Render private Compose env content with literal dollars, quotes and backslashes."""
    options = validate_smtp(options)
    password = validate_smtp_password(password)
    values = {"SMTP_HOST": options["host"], "SMTP_PORT": str(options["port"]),
              "SMTP_TLS_MODE": options["tlsMode"], "SMTP_FROM": "MeetSpace <" + options["from"] + ">",
              "SMTP_USERNAME": options["username"], "SMTP_PASSWORD": password, "SMTP_TIMEOUT": "15s"}
    return ("".join(key + "=" + json.dumps(value, ensure_ascii=False).replace("$", "$$") + "\n"
                    for key, value in sorted(values.items()))).encode("utf-8")


def verify_smtp_environment(path, options):
    """Check root-private SMTP state before every resumed configuration without logging it."""
    no_symlink(path)
    try:
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 32 * 1024:
            fail("SMTP_CONFIG_INVALID")
        raw = path.read_bytes()
        pairs = [line.split("=", 1) for line in raw.decode("utf-8").splitlines()]
        if any(len(pair) != 2 for pair in pairs) or len({pair[0] for pair in pairs}) != len(pairs):
            fail("SMTP_CONFIG_INVALID")
        values = {key: json.loads(value.replace("$$", "$")) for key, value in pairs}
        if raw != smtp_environment(options, values.get("SMTP_PASSWORD")):
            fail("SMTP_CONFIG_INVALID")
    except (OSError, ValueError, UnicodeError, TypeError):
        fail("SMTP_CONFIG_INVALID")


def ubuntu_suite(platform, *, legacy=False, code="UNSUPPORTED_PLATFORM"):
    if not isinstance(platform, dict):
        fail(code)
    # Older v1 plans contain only the architecture in some callers; their sole
    # supported target was Ubuntu 24.04. Never infer Jammy from package text.
    version = platform.get("osVersion", "24.04" if legacy else None)
    arch = platform.get("arch")
    if platform.get("os", "ubuntu" if legacy else None) != "ubuntu" or not isinstance(version, str) or version not in UBUNTU_SUITES or not isinstance(arch, str) or arch not in SUPPORTED_ARCHES:
        fail(code)
    return UBUNTU_SUITES[version]


def validate_docker_packages(packages, version, *, code="RELEASE_INVALID"):
    if not isinstance(packages, dict) or set(packages) != DOCKER_PACKAGES:
        fail(code)
    suffix = "~ubuntu." + version + "~" + UBUNTU_SUITES[version]
    for pin in packages.values():
        if not isinstance(pin, str) or len(pin) > 128 or not re.fullmatch(r"[0-9][A-Za-z0-9.+:~\-]*", pin) or not pin.endswith(suffix):
            fail(code)
    return dict(packages)


def validate_docker_manifest(manifest):
    legacy = manifest.get("dockerPackages")
    if not isinstance(legacy, dict) or set(legacy) != SUPPORTED_ARCHES:
        fail("RELEASE_INVALID")
    for packages in legacy.values():
        validate_docker_packages(packages, "24.04")
    if "dockerPackagesByUbuntu" in manifest:
        matrix = manifest["dockerPackagesByUbuntu"]
        if not isinstance(matrix, dict) or set(matrix) != set(UBUNTU_SUITES):
            fail("RELEASE_INVALID")
        for version, arches in matrix.items():
            if not isinstance(arches, dict) or set(arches) != SUPPORTED_ARCHES:
                fail("RELEASE_INVALID")
            for packages in arches.values():
                validate_docker_packages(packages, version)


def docker_packages(manifest, platform, *, legacy=False, code="RELEASE_INVALID"):
    suite = ubuntu_suite(platform, legacy=legacy, code=code)
    version = next(version for version, name in UBUNTU_SUITES.items() if name == suite)
    if "dockerPackagesByUbuntu" in manifest:
        matrix = manifest["dockerPackagesByUbuntu"]
        if not isinstance(matrix, dict) or not isinstance(matrix.get(version), dict):
            fail(code)
        packages = matrix[version].get(platform["arch"])
    else:
        # A Noble-only signed recipe must never be used to install Jammy.
        if version != "24.04":
            fail(code)
        arches = manifest.get("dockerPackages")
        if not isinstance(arches, dict):
            fail(code)
        packages = arches.get(platform["arch"])
    return validate_docker_packages(packages, version, code=code)


def same_platform(plan, facts):
    previous = plan.get("platform")
    ubuntu_suite(previous, legacy=True, code="PLAN_CHANGED")
    if any(previous.get(key, "ubuntu" if key == "os" else "24.04" if key == "osVersion" else None) != facts.get(key) for key in ("os", "osVersion", "arch")):
        fail("PLAN_CHANGED")


def platform_facts():
    values = {}
    for line in pathlib.Path("/etc/os-release").read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"')
    arch = {"aarch64": "arm64", "x86_64": "amd64"}.get(os.uname().machine, "unsupported")
    platform = {"os": values.get("ID"), "osVersion": values.get("VERSION_ID"), "arch": arch}
    ubuntu_suite(platform)
    if not pathlib.Path("/run/systemd/system").is_dir() or not shutil.which("systemd-run"):
        fail("SYSTEMD_REQUIRED")
    memory = int(re.search(r"^MemTotal:\s+(\d+)", pathlib.Path("/proc/meminfo").read_text(), re.M).group(1))
    return dict(platform, cpus=os.cpu_count() or 0, memoryKiB=memory)


def disk_free():
    path = ROOT if ROOT.exists() else ROOT.parent
    info = os.statvfs(path)
    return info.f_bavail * info.f_frsize


def managed_marker():
    no_symlink(ROOT)
    if not ROOT.exists():
        return None
    marker = ROOT / "managed.json"
    if not marker.exists():
        if any(ROOT.iterdir()):
            fail("EXISTING_INSTALLATION")
        return None
    value = read_json(marker)
    if value.get("schema") != 1 or value.get("owner") != "meetspace-installer-v1":
        fail("EXISTING_INSTALLATION")
    return value


def dns_check(options):
    try:
        addresses = {ipaddress.ip_address(x[4][0]).compressed for x in socket.getaddrinfo(options["domain"], 443, type=socket.SOCK_STREAM)}
    except (OSError, ValueError):
        fail("DNS_FAILED")
    # This profile supports a direct IPv4 VPS, not CDN/proxy/IPv6 or split DNS.
    if addresses != {options["publicIpv4"]}:
        fail("DNS_MISMATCH")
    return sorted(addresses)


def check_ports():
    if not shutil.which("ss"):
        fail("PORT_CHECK_UNAVAILABLE")
    for protocol, ports in PORTS.items():
        output = run(["ss", "-H", "-ln" + ("t" if protocol == "tcp" else "u")], capture=True, code="PORT_CHECK_UNAVAILABLE")
        for line in output.decode("ascii", "replace").splitlines():
            parts = line.split()
            if len(parts) < 5:
                fail("PORT_CHECK_UNAVAILABLE")
            port = parts[3].rsplit(":", 1)[-1]
            if port.isdigit() and int(port) in ports:
                fail("PORT_OCCUPIED")


def docker_facts(*, building=True):
    if not shutil.which("docker"):
        for package in ("docker.io", "docker-compose", "docker-compose-v2", "podman-docker", "containerd", "runc"):
            try:
                installed = run(["dpkg-query", "-W", "-f=${db:Status-Status}", package], capture=True)
                if installed == b"installed":
                    fail("DOCKER_PACKAGE_CONFLICT")
            except InstallError as error:
                if error.code == "DOCKER_PACKAGE_CONFLICT":
                    raise
        return {"installed": False, "version": None, "composeVersion": None}
    version = run(["docker", "version", "--format", "{{.Server.Version}}"], capture=True, code="DOCKER_UNAVAILABLE").decode().strip()
    if not re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", version) or int(version.lstrip("v").split(".")[0]) < 24:
        fail("DOCKER_UNSUPPORTED")
    facts = {"installed": True, "version": version, "composeVersion": None}
    try:
        compose_version = run(["docker", "compose", "version", "--short"], capture=True, code="COMPOSE_REQUIRED").decode().strip()
    except InstallError as error:
        if error.code != "COMPOSE_REQUIRED":
            raise
        facts["installCompose"] = True
    else:
        if not re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", compose_version) or int(compose_version.lstrip("v").split(".")[0]) < 2:
            fail("DOCKER_UNSUPPORTED")
        facts["composeVersion"] = compose_version
    # Check the daemon first: an unavailable Engine is never treated as a missing
    # client plugin. `buildx version` itself does not contact or create a builder.
    if not building:
        return facts
    try:
        buildx_version = run(["docker", "buildx", "version"], capture=True, code="BUILDX_REQUIRED").decode().strip()
    except InstallError as error:
        if error.code != "BUILDX_REQUIRED":
            raise
        facts["installBuildx"] = True
    else:
        if not re.fullmatch(r"(?:github\.com/docker/buildx )?v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?(?: [A-Za-z0-9._-]+)?", buildx_version):
            fail("DOCKER_UNSUPPORTED")
    return facts


def docker_install_required(facts):
    return not facts["installed"] or any(facts.get(flag) for flag, _, _, _ in DOCKER_PLUGINS)


def docker_actions(facts):
    if not facts["installed"]:
        return ["install-docker"]
    return [action for flag, _, action, _ in DOCKER_PLUGINS if facts.get(flag)] or ["reuse-docker"]


def create_plan(options, manifest, *, issued_at=None, source_lock=None, image_lock=None, resource_profile=None, legacy_plan=False):
    recipe = manifest
    if source_lock is not None:
        manifest = locked_manifest(recipe, source_lock)
    options = validate_options(options, manifest)
    facts = platform_facts()
    prebuilt = image_mode(options) == "images"
    if prebuilt:
        validate_image_lock(image_lock, recipe, source_lock, facts["arch"], requested=options.get("imageRelease", "latest"))
    elif image_lock is not None or source_lock and source_lock.get("channel") == "prebuilt":
        fail("PLAN_CHANGED")
    marker = managed_marker()
    free = disk_free()
    previous = read_json(job_directory(marker["jobId"]) / "plan.json") if marker else None
    if legacy_plan:
        resource_profile = "standard"
    elif resource_profile is None:
        if previous:
            resource_profile = plan_resource_profile(previous)
        else:
            standard = RESOURCE_PROFILES["standard"]
            resource_profile = "standard" if facts["cpus"] >= standard["cpus"] and facts["memoryKiB"] >= standard["memoryKiB"] and free >= standard["freshDiskBytes"] else "compact-test"
    resource_profile = plan_resource_profile({"resourceProfile": resource_profile})
    limits = RESOURCE_PROFILES[resource_profile]
    if facts["cpus"] < limits["cpus"] or facts["memoryKiB"] < limits["memoryKiB"]:
        fail("RESOURCES_INSUFFICIENT")
    if free < (2 * 1024 ** 3 if marker else limits["freshDiskBytes"]):
        fail("DISK_SPACE")
    docker = docker_facts(building=False) if prebuilt else docker_facts()
    if marker:
        if marker.get("commit") != manifest["commit"] or marker.get("domain") != options["domain"] or marker.get("releaseSha256") != digest(canonical(manifest)):
            fail("EXISTING_INSTALLATION")
    else:
        for path in ("/opt/meetrix", "/etc/meetrix", "/etc/meetspace"):
            if pathlib.Path(path).exists():
                fail("EXISTING_INSTALLATION")
        check_ports()
        if docker["installed"]:
            for kind in ("container", "volume", "network"):
                args = ["docker", kind, "ls", "--format", "{{.Name}}"] if kind != "container" else ["docker", "ps", "-a", "--format", "{{.Names}}"]
                names = run(args, capture=True).decode().splitlines()
                if any(re.search(r"meetspace|meetrix|meeting", name, re.I) for name in names):
                    fail("EXISTING_INSTALLATION")
    dns = dns_check(options)
    created = int(time.time()) if issued_at is None else issued_at
    plan = {"schema": 1, "installerVersion": VERSION, "releaseVersion": manifest["version"], "commit": manifest["commit"],
            "backendTag": manifest.get("backendTag"), "releaseSha256": digest(canonical(manifest)), "runnerSha256": manifest["runnerSha256"],
            "options": options, "platform": facts, "root": str(ROOT), "project": PROJECT,
            "docker": docker, "dockerPackages": docker_packages(manifest, facts),
            "dnsAddresses": dns, "existingJobId": marker.get("jobId") if marker else None,
            "existingPlanHash": previous["hash"] if marker else None,
            "issuedAt": created, "expiresAt": created + 900,
            "actions": [*docker_actions(docker), "fetch-verified-source", "build-and-pin-images", "generate-secrets", "private-volumes", "backup-and-migrate", "acme-https-wss", "authenticated-turn", "readiness"],
            "ports": {"tcp": PORTS["tcp"], "udp": ["3478", "50010", "49160-49259"]},
            "warnings": ["source-build-not-hermetic", "docker-firewall-bypass", "external-ports-must-be-open", "no-automatic-data-rollback", "local-backup-not-offsite", "no-upstream-release-tag", "turn-restarts-on-certificate-renewal"]}
    if source_lock is not None:
        plan.update(sourceLock=source_lock, recipeSha256=digest(canonical(recipe)))
    if prebuilt:
        plan["imageLock"] = image_lock
        plan["actions"][plan["actions"].index("build-and-pin-images")] = "download-verify-load-images"
        plan["warnings"].remove("source-build-not-hermetic")
    if not legacy_plan:
        plan["resourceProfile"] = resource_profile
    if resource_profile == "compact-test":
        plan["warnings"].append("compact-test-resources")
    if manifest.get("backendTag"):
        plan["warnings"].remove("no-upstream-release-tag")
    plan["hash"] = digest(canonical(plan))
    return plan


@contextlib.contextmanager
def lock_file(path, code="JOB_LOCKED"):
    no_symlink(path)
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail(code)
        yield
    finally:
        os.close(descriptor)


def job_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}", value):
        fail("INVALID_JOB")
    return value


def job_directory(value):
    path = ROOT / "jobs" / job_id(value)
    no_symlink(path)
    return path


def state_write(directory, state, phase=None, error=None, completed=None):
    current = read_json(directory / "state.json") if (directory / "state.json").exists() else {"sequence": 0, "events": [], "completedPhases": []}
    event = {"sequence": current["sequence"] + 1, "at": int(time.time()), "state": state, "phase": phase, "errorCode": error}
    current.update(event)
    current["jobId"] = directory.name
    current["events"] = (current["events"] + [event])[-64:]
    if completed is not None:
        current["completedPhases"] = completed
    write_atomic(directory / "state.json", canonical(current))
    return current


def unit_name(value):
    return "meetspace-install-" + job_id(value)


def unit_active(value):
    try:
        status = run(["systemctl", "show", unit_name(value), "--property=ActiveState", "--value"], capture=True)
        return status.strip() in (b"active", b"activating", b"reloading")
    except InstallError:
        return False


def status(value):
    directory = job_directory(value)
    state = read_json(directory / "state.json")
    plan = read_json(directory / "plan.json")
    if state["state"] == "running" and (directory / "cancel").exists():
        state = dict(state, state="cancel_requested")
    # A dead worker/reboot is observable recovery, never an inferred successful job.
    if state["state"] in ("running", "cancel_requested") and not unit_active(value) and time.time() - state["at"] > 10:
        state = dict(state, state="needs_recovery", errorCode="WORKER_INTERRUPTED")
    return dict(state, planHash=plan["hash"], domain=plan["options"]["domain"], commit=plan["commit"], releaseVersion=plan["releaseVersion"])


def start_unit(value):
    run(["systemd-run", "--quiet", "--collect", "--no-block", "--unit=" + unit_name(value),
         "--property=Type=exec", "--property=StandardOutput=null", "--property=StandardError=null",
         "--property=TimeoutStartSec=infinity", "--property=UMask=0077",
         "/usr/bin/python3", str(ROOT / "installer" / "v1" / "runner.py"), "worker", value], code="WORKER_START")


def initialize_layout(value, plan, manifest, bundle, smtp_password=None):
    # Publish the complete journal and bundle together. An interrupted preparation must
    # not turn the final root into an unknown installation that status/resume cannot read.
    no_symlink(ROOT.parent)
    with tempfile.TemporaryDirectory(prefix="." + ROOT.name + "-init-", dir=ROOT.parent) as temporary:
        prepared = pathlib.Path(temporary)
        no_symlink(prepared)
        directory = prepared / "jobs" / value
        directory.mkdir(mode=0o700, parents=True)
        for name, data in bundle.items():
            write_atomic(prepared / "installer" / "v1" / name, data)
        if "smtp" in plan["options"]:
            write_atomic(prepared / "config" / "smtp.env", smtp_environment(plan["options"]["smtp"], smtp_password))
        write_atomic(directory / "plan.json", canonical(plan))
        if "sourceLock" in plan:
            write_atomic(directory / "source-lock.json", canonical(plan["sourceLock"]))
        state_write(directory, "running", "queued")
        write_atomic(prepared / "managed.json", canonical({"schema": 1, "owner": "meetspace-installer-v1", "jobId": value,
                     "domain": plan["options"]["domain"], "commit": manifest["commit"], "version": manifest["version"],
                     "sourceSha256": manifest["sourceSha256"], "releaseSha256": digest(canonical(manifest))}))
        # File writes fsync their direct parent. Persist every newly created directory
        # entry as well before making the complete tree visible at its final location.
        folders = [directory, prepared / "jobs", prepared / "installer" / "v1", prepared / "installer", prepared]
        if "smtp" in plan["options"]:
            folders.insert(0, prepared / "config")
        for folder in folders:
            descriptor = os.open(folder, os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        no_symlink(ROOT)
        if ROOT.exists() and any(ROOT.iterdir()):
            fail("EXISTING_INSTALLATION")
        # Same-filesystem rename also replaces an existing empty root atomically.
        os.replace(prepared, ROOT)
        descriptor = os.open(ROOT.parent, os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def apply(request, manifest, bundle):
    plan = request.get("plan")
    if not isinstance(plan, dict) or request.get("confirmedPlanHash") != plan.get("hash"):
        fail("CONFIRMATION_REQUIRED")
    contents = {key: value for key, value in plan.items() if key != "hash"}
    if digest(canonical(contents)) != plan.get("hash"):
        fail("PLAN_CHANGED")
    recipe = manifest
    manifest = manifest_for_plan(recipe, plan)
    value = job_id(request.get("jobId"))
    # Creation of the lock is itself an approved apply action; plan never creates it.
    with lock_file(pathlib.Path("/run/meetspace-installer-control.lock")):
        marker = managed_marker()
        if marker:
            if marker.get("jobId") == value:
                existing = read_json(job_directory(value) / "plan.json")
                if existing["hash"] != plan["hash"]:
                    fail("PLAN_CHANGED")
                return status(value)
            fail("EXISTING_INSTALLATION")
        now = int(time.time())
        if not isinstance(plan.get("issuedAt"), int) or now > plan.get("expiresAt", 0) or plan["issuedAt"] > now + 30:
            fail("PLAN_EXPIRED")
        fresh = create_plan(plan.get("options"), recipe, issued_at=plan["issuedAt"], source_lock=plan.get("sourceLock"),
                            image_lock=plan.get("imageLock"), resource_profile=plan_resource_profile(plan), legacy_plan="resourceProfile" not in plan)
        if fresh["hash"] != plan["hash"]:
            fail("PLAN_CHANGED")
        if request.get("acceptAcmeTerms") is not True:
            fail("ACME_TERMS_REQUIRED")
        if docker_install_required(fresh["docker"]) and request.get("confirmDockerInstall") is not True:
            fail("DOCKER_CONFIRMATION_REQUIRED")
        smtp_password = request.get("smtpPassword")
        if "smtp" in fresh["options"]:
            validate_smtp_password(smtp_password)
        elif smtp_password not in (None, ""):
            fail("INVALID_SMTP")
        initialize_layout(value, plan, manifest, bundle, smtp_password)
        directory = job_directory(value)
        try:
            start_unit(value)
        except InstallError:
            state_write(directory, "needs_recovery", "queued", "WORKER_START")
        return status(value)


def control(action, request, manifest):
    value = job_id(request.get("jobId"))
    directory = job_directory(value)
    plan = read_json(directory / "plan.json")
    manifest = manifest_for_plan(manifest, plan)
    if plan["releaseSha256"] != digest(canonical(manifest)) or plan["hash"] != request.get("planHash"):
        fail("PLAN_CHANGED")
    if action == "status":
        return status(value)
    with lock_file(pathlib.Path("/run/meetspace-installer-control.lock")):
        current = status(value)
        if action == "cancel":
            if current["state"] not in TERMINAL:
                write_atomic(directory / "cancel", b"requested\n")
            # Worker owns journal; cancellation is a durable independent signal, no lost updates.
            return dict(status(value), state="cancel_requested") if current["state"] not in TERMINAL else current
        if action == "resume":
            if current["state"] == "completed" or unit_active(value):
                return current
            if request.get("confirmedPlanHash") != plan["hash"]:
                fail("CONFIRMATION_REQUIRED")
            validate_options(plan["options"], manifest)
            facts = platform_facts()
            same_platform(plan, facts)
            limits = RESOURCE_PROFILES[plan_resource_profile(plan)]
            if facts["cpus"] < limits["cpus"] or facts["memoryKiB"] < limits["memoryKiB"]:
                fail("RESOURCES_INSUFFICIENT")
            dns_check(plan["options"])
            if disk_free() < 2 * 1024 ** 3:
                fail("DISK_SPACE")
            (directory / "cancel").unlink(missing_ok=True)
            state_write(directory, "running", "resuming")
            start_unit(value)
            return status(value)
        fail("INVALID_REQUEST")


def download(url, destination, expected, maximum=100 * 1024 * 1024):
    if not url.startswith("https://"):
        fail("DOWNLOAD_FAILED")
    temporary = pathlib.Path(str(destination) + ".partial")
    no_symlink(temporary)
    total, checksum = 0, hashlib.sha256()
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "MeetSpace-Installer/1"})
        with urllib.request.urlopen(request, timeout=30) as response, open(temporary, "wb") as output:
            if not response.url.startswith("https://"):
                fail("DOWNLOAD_FAILED")
            while True:
                chunk = response.read(128 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > maximum:
                    fail("DOWNLOAD_LIMIT")
                checksum.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if checksum.hexdigest() != expected:
            fail("RELEASE_CHECKSUM")
        os.replace(temporary, destination)
    except (OSError, urllib.error.URLError):
        fail("DOWNLOAD_FAILED")
    finally:
        temporary.unlink(missing_ok=True)


def extract_source(archive, destination, commit):
    prefix = "meet-space-" + commit
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 30000 or sum(x.size for x in members) > 256 * 1024 * 1024:
            fail("SOURCE_INVALID")
        for member in members:
            pieces = pathlib.PurePosixPath(member.name).parts
            if not pieces or pieces[0] != prefix or ".." in pieces or member.name.startswith("/") or not (member.isfile() or member.isdir()):
                fail("SOURCE_INVALID")
        # Extract only ordinary files; ignore all archive owner/mode/PAX metadata.
        for member in members:
            relative = pathlib.PurePosixPath(member.name).parts[1:]
            if not relative:
                continue
            target = destination.joinpath(*relative)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o755)
                os.chmod(target, 0o755)
            else:
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
                with source.extractfile(member) as stream:
                    # Public source permissions must survive COPY into non-root runtime images.
                    write_atomic(target, stream.read(), 0o755 if member.mode & 0o111 else 0o644)


def pinned_dockerfile(content, bases, resource_profile="standard"):
    """Pin build stages and retain architecture-scoped Go/npm caches across retries."""
    resource_profile = plan_resource_profile({"resourceProfile": resource_profile})
    lines, stages = [], {}
    source_lines = iter(content.splitlines())
    kind, cache_key = None, None
    for line in source_lines:
        if re.match(r"^\s*FROM\s+", line, re.I):
            fields = line.split()
            fields[0] = "FROM"
            index = 2 if fields[1].startswith("--platform=") else 1
            if len(fields) <= index or len(fields) not in (index + 1, index + 3):
                fail("SOURCE_INCOMPATIBLE")
            image = fields[index]
            kind, cache_key = stages.get(image.lower(), (None, None))
            if image.lower() not in stages and image != "scratch":
                if image not in bases:
                    fail("UNPINNED_BASE_IMAGE")
                fields[index] = bases[image]
                kind = "go" if re.match(r"(?:docker\.io/library/)?golang:", image) else "node" if re.match(r"(?:docker\.io/library/)?node:", image) else None
                cache_key = digest(bases[image].encode())[:16]
            if len(fields) == index + 3:
                if fields[index + 1].lower() != "as" or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", fields[index + 2]):
                    fail("SOURCE_INCOMPATIBLE")
                fields[index + 1] = "AS"
                stages[fields[index + 2].lower()] = (kind, cache_key)
            line = " ".join(fields)
            lines.append(line)
            if kind in ("go", "node"):
                lines.append("ARG TARGETARCH")
            if resource_profile == "compact-test":
                # These ENV instructions precede RUN and take effect in the actual
                # compiler/npm processes; unused build args alone would not do so.
                if kind == "go":
                    lines.append("ENV GOMAXPROCS=2 GOFLAGS=-p=1 GOMEMLIMIT=1536MiB")
                elif kind == "node":
                    lines.append("ENV NODE_OPTIONS=--max-old-space-size=1536 UV_THREADPOOL_SIZE=2 npm_config_jobs=1 GOMAXPROCS=2")
            continue
        if kind in ("go", "node") and line.strip() == "ARG TARGETARCH":
            continue
        if re.match(r"^\s*RUN\s+", line, re.I):
            instruction = [line]
            while instruction[-1].rstrip().endswith("\\"):
                continuation = next(source_lines, None)
                if continuation is None:
                    fail("SOURCE_INCOMPATIBLE")
                instruction.append(continuation)
            joined = "\n".join(instruction)
            targets = []
            if kind == "go" and re.search(r"\bgo\s+(mod|get|build|install|test|run|generate|list)\b", joined):
                targets.append(("modules", "/go/pkg/mod"))
                if re.search(r"\bgo\s+(get|build|install|test|run|generate|list)\b", joined):
                    targets.append(("build", "/root/.cache/go-build"))
            elif kind == "node" and re.search(r"\bnpm\s+(ci|install)\b", joined):
                targets.append(("npm", "/root/.npm"))
            mounts = ["--mount=type=cache,id=meetspace-installer-" + kind + "-" + cache_key + "-" + name
                      + "-${TARGETARCH},target=" + target + ",sharing=locked"
                      for name, target in targets if not re.search(r"(?:target|dst|destination)=" + re.escape(target) + r"(?:,|\s|$)", joined)]
            if mounts:
                instruction[0] = re.sub(r"^(\s*RUN\s+)", lambda match: match[1] + " ".join(mounts) + " ", instruction[0], count=1, flags=re.I)
            # A later ARG with a default can override an earlier ENV in RUN.
            # Reassert the budget after all upstream declarations, immediately
            # before each command that executes in a language build stage.
            if resource_profile == "compact-test":
                if kind == "go":
                    lines.append("ENV GOMAXPROCS=2 GOFLAGS=-p=1 GOMEMLIMIT=1536MiB")
                elif kind == "node":
                    lines.append("ENV NODE_OPTIONS=--max-old-space-size=1536 UV_THREADPOOL_SIZE=2 npm_config_jobs=1 GOMAXPROCS=2")
            lines.extend(instruction)
            continue
        lines.append(line)
    return "\n".join(lines) + "\n"


class Worker:
    def __init__(self, value, manifest):
        self.directory = job_directory(value)
        self.plan = read_json(self.directory / "plan.json")
        self.manifest = manifest_for_plan(manifest, self.plan)
        self.options = self.plan["options"]
        self.release = ROOT / "releases" / self.manifest["commit"]
        self.config = ROOT / "config"
        self.compose_file = self.config / "compose.json"
        self.images_file = self.directory / "images.json"
        self.env_file = self.config / "server.env"
        if "smtp" in self.options:
            verify_smtp_environment(self.config / "smtp.env", self.options["smtp"])

    @property
    def compact(self):
        return plan_resource_profile(getattr(self, "plan", {})) == "compact-test"

    def check_build_space(self):
        if disk_free() < (6 if self.compact else 8) * 1024 ** 3:
            fail("DISK_SPACE")

    def compose(self, *args, capture=False, timeout=300, code="COMPOSE_FAILED", data=None):
        return run(["docker", "compose", "--project-name", PROJECT, "--project-directory", str(self.release),
                    "--env-file", str(self.env_file), "-f", str(self.compose_file), *args],
                   capture=capture, timeout=timeout, code=code, data=data)

    def phase_docker(self):
        suite = ubuntu_suite(self.plan.get("platform"), legacy=True, code="PLAN_CHANGED")
        if self.plan.get("dockerPackages") != docker_packages(self.manifest, self.plan.get("platform"), legacy=True, code="PLAN_CHANGED"):
            fail("PLAN_CHANGED")
        building = image_mode(self.plan.get("options", {})) == "source"
        facts = docker_facts() if building else docker_facts(building=False)
        if not docker_install_required(facts):
            return
        key_target = pathlib.Path("/etc/apt/keyrings/meetspace-docker.asc")
        source_target = pathlib.Path("/etc/apt/sources.list.d/meetspace-docker.sources")
        key_target.parent.mkdir(mode=0o755, exist_ok=True)
        os.chmod(key_target.parent, 0o755)
        key = (ROOT / "installer" / "v1" / "docker.asc").read_bytes()
        if digest(key) != self.manifest["dockerKeySha256"]:
            fail("RELEASE_CHECKSUM")
        sources = ("Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: " + suite + "\nComponents: stable\n"
                   "Architectures: " + self.plan["platform"]["arch"] + "\nSigned-By: " + str(key_target) + "\n").encode()
        for path, data in ((key_target, key), (source_target, sources)):
            if path.exists() and path.read_bytes() != data:
                fail("APT_SOURCE_CONFLICT")
            write_atomic(path, data, 0o644)
        run(["apt-get", "-o", "DPkg::Lock::Timeout=120", "update", "-qq"], timeout=600, code="DOCKER_INSTALL_FAILED")
        if facts["installed"]:
            packages = [package + "=" + self.plan["dockerPackages"][package]
                        for flag, package, _, _ in DOCKER_PLUGINS if facts.get(flag)]
            run(["apt-get", "-o", "DPkg::Lock::Timeout=120", "install", "-y", "--no-install-recommends", "--no-upgrade", *packages],
                timeout=900, code="DOCKER_INSTALL_FAILED", env=dict(SAFE_ENV, NEEDRESTART_MODE="l"))
        else:
            packages = [key + "=" + value for key, value in sorted(self.plan["dockerPackages"].items())]
            run(["apt-get", "-o", "DPkg::Lock::Timeout=120", "install", "-y", "--no-install-recommends", *packages], timeout=900, code="DOCKER_INSTALL_FAILED")
            run(["systemctl", "enable", "--now", "docker"], timeout=90, code="DOCKER_INSTALL_FAILED")
        current = docker_facts() if building else docker_facts(building=False)
        if not current["installed"]:
            fail("DOCKER_UNAVAILABLE")
        for flag, _, _, error_code in DOCKER_PLUGINS:
            if current.get(flag):
                fail(error_code)

    def phase_source(self):
        self.release.mkdir(mode=0o700, parents=True, exist_ok=True)
        archive = self.directory / "source.tar.gz"
        if not archive.exists() or digest(archive.read_bytes()) != self.manifest["sourceSha256"]:
            download(self.manifest["sourceUrl"], archive, self.manifest["sourceSha256"])
        # Re-extract the exact snapshot on retry; application data never live here.
        extract_source(archive, self.release, self.manifest["commit"])
        for name, expected in self.manifest["sourceFiles"].items():
            path = self.release / name
            if digest(path.read_bytes()) != expected:
                fail("SOURCE_INVALID")

    def image_details(self, reference):
        raw = run(["docker", "image", "inspect", "--format", "{{json .}}", reference], capture=True, code="IMAGE_UNAVAILABLE")
        value = strict_json(raw)
        if value.get("Architecture") != self.plan["platform"]["arch"] or not re.fullmatch(r"sha256:[a-f0-9]{64}", value.get("Id", "")):
            fail("IMAGE_INVALID")
        return value

    def check_cancel(self):
        if (self.directory / "cancel").exists():
            fail("CANCELLED")

    def image_timing(self, service, operation, started):
        """Checkpoint public image timings independently from secret-free phase events."""
        path = self.directory / "image-timings.json"
        timings = read_json(path) if path.exists() else {}
        elapsed = round(max(0, time.monotonic() - started), 3)
        if operation == "cached" and service in timings:
            timings[service]["reuseCount"] = timings[service].get("reuseCount", 0) + 1
            timings[service]["lastReuseSeconds"] = elapsed
        else:
            timings[service] = {"operation": operation, "elapsedSeconds": elapsed, "completedAt": int(time.time())}
        write_atomic(path, canonical(timings))
        state_write(self.directory, "running", "images:" + service)

    def phase_images(self):
        if image_mode(getattr(self, "options", {})) == "images":
            return self.phase_prebuilt_images()
        # Derive an argument, never rewrite the frozen release or its plan hash on resume.
        build_time = build_timestamp_utc(self.manifest.get("builtAt"))
        images = read_json(self.images_file) if self.images_file.exists() else {}
        for service, reference in self.manifest["images"].items():
            self.check_cancel()
            started = time.monotonic()
            if service in images:
                details = self.image_details(reference)
                if details["Id"] != images[service]:
                    fail("IMAGE_CHANGED")
                self.image_timing(service, "cached", started)
                continue
            self.check_build_space()
            run(["docker", "pull", "--platform", "linux/" + self.plan["platform"]["arch"], reference], timeout=900, code="IMAGE_PULL_FAILED")
            details = self.image_details(reference)
            if service in images and images[service] != details["Id"]:
                fail("IMAGE_CHANGED")
            images[service] = details["Id"]
            write_atomic(self.images_file, canonical(images))
            self.image_timing(service, "pull", started)
        for service in APP_SERVICES:
            self.check_cancel()
            started = time.monotonic()
            recipe = self.manifest["dockerfiles"][service]
            dockerfile = self.release / recipe["path"]
            if digest(dockerfile.read_bytes()) != recipe["sha256"]:
                fail("SOURCE_INVALID")
            if service in images:
                details = self.image_details(images[service])
                labels = details.get("Config", {}).get("Labels", {}) or {}
                if labels.get("io.meetspace.recipe") != self.plan["releaseSha256"] or labels.get("org.opencontainers.image.revision") != self.manifest["commit"]:
                    fail("IMAGE_CHANGED")
                if labels.get("io.meetspace.resource-profile", "standard") != plan_resource_profile(self.plan):
                    fail("IMAGE_CHANGED")
                if details["Id"] != images[service]:
                    fail("IMAGE_CHANGED")
                self.image_timing(service, "cached", started)
                continue
            self.check_build_space()
            prepared = self.directory / (service + ".Dockerfile")
            write_atomic(prepared, pinned_dockerfile(dockerfile.read_text(), self.manifest["baseImages"], plan_resource_profile(self.plan)).encode())
            context = self.release / recipe["context"]
            tag = "meetspace-installer/" + service + ":" + self.manifest["commit"][:12] + "-" + self.plan["releaseSha256"][:12]
            if self.compact:
                tag += "-compact-test"
            # Docker output may contain build/dependency data: discard, expose fixed phase events only.
            run(["docker", "build", "--platform", "linux/" + self.plan["platform"]["arch"], "--target", "runtime", "--file", str(prepared),
                 "--tag", tag, "--build-arg", "BUILD_VERSION=" + self.manifest["version"], "--build-arg", "BUILD_COMMIT=" + self.manifest["commit"],
                 "--build-arg", "BUILD_TIME=" + build_time, "--build-arg", "TARGETARCH=" + self.plan["platform"]["arch"],
                 "--build-arg", "CLIENT_TELEMETRY_ENABLED=false", "--label", "org.opencontainers.image.revision=" + self.manifest["commit"],
                 "--label", "io.meetspace.recipe=" + self.plan["releaseSha256"],
                 "--label", "io.meetspace.resource-profile=" + plan_resource_profile(self.plan), str(context)],
                timeout=7200 if self.compact else 3600, code="IMAGE_BUILD_FAILED", env=dict(SAFE_ENV, DOCKER_BUILDKIT="1"))
            details = self.image_details(tag)
            images[service] = details["Id"]
            write_atomic(self.images_file, canonical(images))
            self.image_timing(service, "build", started)

    def phase_prebuilt_images(self):
        lock = self.plan["imageLock"]
        self.check_cancel()
        state_write(self.directory, "running", "images:download")
        archive = self.directory / "prebuilt.tar.gz"
        if not archive.exists() or archive.stat().st_size != lock["sizeBytes"] or file_digest(archive) != lock["sha256"]:
            self.check_build_space()
            download_prebuilt(lock, archive, self.check_cancel)
        destination = self.directory / "prebuilt"
        no_symlink(destination)
        state_write(self.directory, "running", "images:verify")
        if not destination.exists():
            self.check_build_space()
            with tempfile.TemporaryDirectory(prefix=".prebuilt-", dir=self.directory) as temporary:
                bundle = extract_prebuilt(archive, pathlib.Path(temporary), self.check_cancel)
                package = verify_prebuilt_package(bundle, lock)
                os.replace(bundle, destination)
        else:
            package = verify_prebuilt_package(destination, lock)
        self.check_cancel()
        images = read_json(self.images_file) if self.images_file.exists() else {}
        required = set(APP_SERVICES) | {"postgres", "redis", "rabbitmq", "coturn", "proxy"}
        # Import only once after verification; restart validates every already recorded image ID.
        if not required.issubset(images):
            self.check_build_space()
            state_write(self.directory, "running", "images:load")
            run(["docker", "load", "--input", str(destination / "release" / "images.tar")], timeout=1800, code="IMAGE_LOAD_FAILED")
        for service in sorted(required):
            self.check_cancel()
            started = time.monotonic()
            details = self.image_details(package["images"][service])
            verify_prebuilt_labels(service, details.get("Config", {}).get("Labels"), package)
            if service in images and images[service] != details["Id"]:
                fail("IMAGE_CHANGED")
            images[service] = details["Id"]
            write_atomic(self.images_file, canonical(images))
            self.image_timing(service, "load", started)
        # ACME tooling is pinned by the signed Android recipe, separate from server images.
        reference = self.manifest["images"]["certbot"]
        if "certbot" not in images:
            self.check_build_space()
            run(["docker", "pull", "--platform", lock["platform"], reference], timeout=900, code="IMAGE_PULL_FAILED")
        details = self.image_details(reference)
        if "certbot" in images and images["certbot"] != details["Id"]:
            fail("IMAGE_CHANGED")
        images["certbot"] = details["Id"]
        write_atomic(self.images_file, canonical(images))
        write_atomic(self.directory / "prebuilt-receipt.json", canonical({"imageLock": lock,
            "manifestSha256": file_digest(destination / "release" / "release.json"), "images": images}))

    def environment(self):
        out = {}
        for line in self.env_file.read_text().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                out[key] = value
        return out

    def compact_environment(self):
        if not self.compact:
            return {}
        # Keep every service/feature available while admitting one expensive job
        # at a time. Per-service heap limits are added in the Compose document.
        return {"GOMAXPROCS": "2", "GOMEMLIMIT": "96MiB", "DB_MAX_OPEN": "3", "DB_MAX_IDLE": "1",
                "RECORDING_MAX_ACTIVE": "1", "RECORDING_FFMPEG_CONCURRENCY": "1", "RECORDING_DISK_RESERVE_MIB": "2048",
                "PRODUCT_DELIVERY_CONCURRENCY": "1", "PRODUCT_CALENDAR_CONCURRENCY": "1", "TRANSCRIPTION_CONCURRENCY": "1",
                "AI_JOB_CONCURRENCY": "1", "AI_CHUNK_CONCURRENCY": "1", "EMBEDDING_CONCURRENCY": "1",
                "LIVE_MAX_CONFERENCES": "1", "LIVE_MAX_SESSIONS": "10"}

    def phase_configuration(self):
        self.config.mkdir(mode=0o700, parents=True, exist_ok=True)
        if "smtp" in self.options:
            verify_smtp_environment(self.config / "smtp.env", self.options["smtp"])
        for folder in ("recordings", "tls", "acme", "challenge", "backups", *(("product-tmp",) if self.compact else ())):
            path = ROOT / folder
            path.mkdir(mode=0o755 if folder in ("tls", "challenge") else 0o700, exist_ok=True)
            if folder in ("tls", "challenge"):
                os.chmod(path, 0o755)
            if folder in ("recordings", "product-tmp"):
                os.chown(path, 1000, 1000)
                os.chmod(path, 0o700)
        # Docker-assigned subnet avoids a hardcoded network that could collide with host/VPN routes.
        network_name = PROJECT + "_private"
        try:
            network = strict_json(run(["docker", "network", "inspect", "--format", "{{json .}}", network_name], capture=True))
        except InstallError:
            run(["docker", "network", "create", "--label", "io.meetspace.installer=" + self.directory.name, network_name])
            network = strict_json(run(["docker", "network", "inspect", "--format", "{{json .}}", network_name], capture=True))
        if network.get("Labels", {}).get("io.meetspace.installer") != self.directory.name:
            fail("EXISTING_INSTALLATION")
        subnet = network["IPAM"]["Config"][0]["Subnet"]
        ipaddress.ip_network(subnet)
        if not self.env_file.exists():
            values = {}
            for line in (self.release / ".env.production.example").read_text().splitlines():
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    if re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
                        values[key] = value
            for key in ("JWT_SECRET", "MEDIA_TICKET_SECRET", "MEDIA_INTERNAL_SECRET", "WORKER_INTERNAL_SECRET", "METRICS_SECRET", "TURN_SHARED_SECRET", "POSTGRES_PASSWORD", "REDIS_PASSWORD", "RABBIT_MQ_PASSWORD", "MINIO_ROOT_PASSWORD"):
                values[key] = secrets.token_hex(32)
            domain, public_ip = self.options["domain"], self.options["publicIpv4"]
            values.update({"PROVIDER_TOKEN_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
                "BUILD_VERSION": self.manifest["version"], "POSTGRES_USER": "meetspace", "POSTGRES_DB": "meetspace", "RABBIT_MQ_USER": "meetspace",
                "MINIO_ROOT_USER": "ms" + secrets.token_hex(10), "MINIO_ROOT_PASSWORD": secrets.token_hex(32),
                "PUBLIC_FRONTEND_URL": "https://" + domain, "MINIO_PUBLIC_ENDPOINT": "https://" + domain, "WS_ALLOWED_ORIGINS": "https://" + domain,
                "HTTP_TRUSTED_PROXIES": subnet, "MEDIA_NAT_IPS": public_ip, "TURN_PUBLIC_IP": public_ip, "TURN_REALM": domain,
                "TURN_URLS": "turn:" + domain + ":3478?transport=udp,turn:" + domain + ":3478?transport=tcp,turns:" + domain + ":5349?transport=tcp",
                "RECORDER_STORAGE_DIR": str(ROOT / "recordings"), "TLS_CERT_DIR": str(ROOT / "tls"), "TURN_TLS_CERT_DIR": str(ROOT / "tls"),
                "DB_MAX_OPEN": "10", "DB_MAX_IDLE": "5", "MEDIA_MAX_ROOMS": "10", "MEDIA_MAX_PEERS": "10",
                "REMINDER_OFFSETS": "3h"})
            values.update(self.compact_environment())
            write_atomic(self.env_file, ("\n".join(key + "=" + value for key, value in sorted(values.items())) + "\n").encode())
        values = self.environment()
        if any(values.get(key) != value for key, value in self.compact_environment().items()):
            fail("CONFIG_CHANGED")
        if values.get("PUBLIC_FRONTEND_URL") != "https://" + self.options["domain"] or values.get("HTTP_TRUSTED_PROXIES") != subnet:
            fail("CONFIG_CHANGED")
        route_source = self.release / "dockers/production/routes.conf"
        (self.config / "proxy").mkdir(mode=0o755, exist_ok=True)
        os.chmod(self.config / "proxy", 0o755)
        write_atomic(self.config / "proxy" / "routes.inc", route_source.read_bytes(), 0o644)
        turn = (self.release / "dockers/turn/turnserver.conf").read_text()
        turn += "\nstatic-auth-secret=" + values["TURN_SHARED_SECRET"] + "\nexternal-ip=" + self.options["publicIpv4"] + "\nrealm=" + self.options["domain"] + "\nmin-port=49160\nmax-port=49259\n"
        write_atomic(self.config / "turnserver.conf", turn.encode(), 0o600)
        os.chown(self.config / "turnserver.conf", 0, 1000)
        os.chmod(self.config / "turnserver.conf", 0o640)
        # Bind a single root-managed file; its contents are intentionally readable by Coturn UID 1000.
        self.write_compose(values, read_json(self.images_file))
        if not (self.config / "proxy" / "default.conf").exists():
            self.write_nginx(tls=False)
        self.compose("config", "--quiet")

    def write_nginx(self, tls):
        challenge = "server { listen 80; server_name " + self.options["domain"] + "; access_log off; location /.well-known/acme-challenge/ { root /var/www/certbot; } location / { " + ("return 308 https://$host$request_uri;" if tls else "return 404;") + " } }\n"
        if tls:
            challenge += '''map $http_upgrade $connection_upgrade { default upgrade; '' close; }
server {
 listen 443 ssl; server_name DOMAIN;
 ssl_certificate /run/certs/fullchain.pem; ssl_certificate_key /run/certs/privkey.pem;
 ssl_protocols TLSv1.2 TLSv1.3; server_tokens off; access_log off; error_log /dev/null crit;
 client_max_body_size 1m; client_body_timeout 30s;
 resolver 127.0.0.11 valid=5s ipv6=off;
 set $api_upstream api:8085; set $frontend_upstream frontend:8080;
 add_header Strict-Transport-Security "max-age=31536000" always;
 add_header X-Content-Type-Options nosniff always; add_header Referrer-Policy no-referrer always;
 include /etc/nginx/conf.d/routes.inc;
}
'''.replace("DOMAIN", self.options["domain"])
        write_atomic(self.config / "proxy" / "default.conf", challenge.encode(), 0o644)

    def write_compose(self, values, images):
        private = ["private"]
        common = {"env_file": [str(self.env_file)], "restart": "unless-stopped", "stop_grace_period": "45s", "init": True,
                  "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"], "pids_limit": 512, "networks": private,
                  "labels": {"io.meetspace.installer": self.directory.name}, "logging": {"driver": "json-file", "options": {"max-size": "10m", "max-file": "3"}}}
        services = {}
        ports = {"api": 8085, "media-worker": 8091, "worker": 8090, "product-worker": 8092, "live-worker": 8093}
        memory = {"api": "512m", "media-worker": "1g", "worker": "2g", "product-worker": "1g", "live-worker": "512m"}
        for name, port in ports.items():
            services[name] = dict(common, image=images[name], read_only=True, mem_limit=memory[name], cpus=2,
                healthcheck={"test": ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:" + str(port) + "/health/ready"], "interval": "5s", "timeout": "3s", "retries": 18, "start_period": "30s"})
        if "smtp" in getattr(self, "options", {}):
            for name in ("api", "product-worker"):
                services[name].update(env_file=[str(self.env_file), str(self.config / "smtp.env")],
                                      environment={"EMAIL_PROVIDER_MODE": "smtp"})
        services["media-worker"]["ports"] = ["50010:50010/udp", "50010:50010/tcp"]
        services["worker"].update(user="1000:1000", entrypoint="/app/go-recorder-worker", read_only=False,
            volumes=[str(ROOT / "recordings") + ":/storage"], environment={"WEBRTC_UDP_PORT": "0", "WEBRTC_TCP_PORT": "0"})
        services["product-worker"]["tmpfs"] = ["/tmp:size=512m,mode=1777"]
        services["live-worker"]["tmpfs"] = ["/tmp:size=16m,mode=1777"]
        services["frontend"] = {"image": images["frontend"], "restart": "unless-stopped", "networks": private, "mem_limit": "128m",
            "environment": {key: values[key] for key in ("APP_ENV", "PUBLIC_FRONTEND_URL", "MINIO_PUBLIC_ENDPOINT")},
            "labels": common["labels"], "logging": common["logging"]}
        services["proxy"] = {"image": images["proxy"], "restart": "unless-stopped", "networks": private, "mem_limit": "128m", "ports": ["80:80", "443:443"],
            "volumes": [str(self.config / "proxy") + ":/etc/nginx/conf.d:ro",
                        str(ROOT / "tls") + ":/run/certs:ro", str(ROOT / "challenge") + ":/var/www/certbot:ro"], "labels": common["labels"], "logging": common["logging"]}
        services["postgres"] = {"image": images["postgres"], "restart": "unless-stopped", "env_file": [str(self.env_file)], "networks": private,
            "volumes": ["postgres:/var/lib/postgresql/data"], "mem_limit": "512m", "command": ["postgres", "-c", "max_connections=80", "-c", "shared_buffers=128MB"],
            "healthcheck": {"test": ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"], "interval": "5s", "timeout": "3s", "retries": 24}, "labels": common["labels"]}
        services["redis"] = {"image": images["redis"], "restart": "unless-stopped", "env_file": [str(self.env_file)], "networks": private, "mem_limit": "192m",
            "command": ["/bin/sh", "-c", 'exec redis-server --requirepass "$${REDIS_PASSWORD}" --save "" --appendonly no --maxmemory 128mb --maxmemory-policy noeviction'],
            "healthcheck": {"test": ["CMD-SHELL", 'REDISCLI_AUTH="$${REDIS_PASSWORD}" redis-cli ping | grep -q PONG'], "interval": "5s", "timeout": "3s", "retries": 24}, "labels": common["labels"]}
        services["rabbitmq"] = {"image": images["rabbitmq"], "restart": "unless-stopped", "networks": private, "volumes": ["rabbitmq:/var/lib/rabbitmq"], "mem_limit": "512m",
            "environment": {"RABBITMQ_DEFAULT_USER": values["RABBIT_MQ_USER"], "RABBITMQ_DEFAULT_PASS": values["RABBIT_MQ_PASSWORD"], "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS": "+S 2:2"},
            "healthcheck": {"test": ["CMD", "rabbitmq-diagnostics", "-q", "ping"], "interval": "5s", "timeout": "4s", "retries": 30}, "labels": common["labels"]}
        services["minio"] = dict(common, image=images["minio"], read_only=True, tmpfs=["/tmp:size=32m,mode=1777"], volumes=["objects:/data"], mem_limit="512m",
            environment={"AWS_ACCESS_KEY_ID": values["MINIO_ROOT_USER"], "AWS_SECRET_ACCESS_KEY": values["MINIO_ROOT_PASSWORD"], "S3_BUCKET": values["MINIO_BUCKET"], "S3_MAX_VOLUMES": "16"},
            healthcheck={"test": ["CMD-SHELL", 'wget -q -S -O /dev/null http://127.0.0.1:9000/ 2>&1 | grep -q "403 Forbidden"'], "interval": "5s", "timeout": "3s", "retries": 24, "start_period": "30s"})
        # The pinned Coturn binary carries cap_net_bind_service; its bounding capability
        # must remain present for exec, even on our high ports and with no-new-privileges.
        services["coturn"] = {"image": images["coturn"], "user": "1000:1000", "entrypoint": ["turnserver", "-c", "/run/config/turnserver.conf"],
            "restart": "unless-stopped", "networks": private, "mem_limit": "256m", "cap_drop": ["ALL"], "cap_add": ["NET_BIND_SERVICE"], "security_opt": ["no-new-privileges:true"],
            "volumes": [str(self.config / "turnserver.conf") + ":/run/config/turnserver.conf:ro", str(ROOT / "tls") + ":/run/certs:ro"],
            "ports": ["3478:3478/udp", "3478:3478/tcp", "5349:5349/tcp", "49160-49259:49160-49259/udp"], "labels": common["labels"], "logging": common["logging"]}
        services["migrate"] = dict(common, image=images["api"] , profiles=["migration"], restart="no", read_only=True, entrypoint=["/app/recorder-migrate", "migrate"])
        if self.compact:
            # 2128 MiB total for long-running services leaves at least 944 MiB
            # for the host at this profile's minimum 3 GiB of usable RAM.
            memory = {"api": 160, "media-worker": 256, "worker": 512, "product-worker": 256, "live-worker": 96,
                      "frontend": 48, "proxy": 48, "postgres": 160, "redis": 64, "rabbitmq": 224, "minio": 224, "coturn": 80, "migrate": 160}
            heaps = {"api": 96, "media-worker": 160, "worker": 80, "product-worker": 128, "live-worker": 64, "minio": 128, "migrate": 96}
            for name, budget in memory.items():
                services[name].update(mem_limit=str(budget) + "m", memswap_limit=str(budget) + "m", cpus=1.25 if name == "worker" else 1 if name == "media-worker" else 0.5)
                if name in heaps:
                    services[name]["environment"] = dict(services[name].get("environment", {}), GOMAXPROCS="2", GOMEMLIMIT=str(heaps[name]) + "MiB")
            # Transcription can download a 1 GiB input plus audio into /tmp.
            # Use job-owned disk here instead of charging that data to RAM.
            services["product-worker"].pop("tmpfs", None)
            services["product-worker"]["volumes"] = [str(ROOT / "product-tmp") + ":/tmp"]
            services["product-worker"]["user"] = "1000:1000"
            services["postgres"]["command"] = ["postgres", "-c", "max_connections=40", "-c", "shared_buffers=32MB"]
            services["redis"]["command"][-1] = services["redis"]["command"][-1].replace("--maxmemory 128mb", "--maxmemory 32mb")
            services["rabbitmq"]["environment"]["RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS"] = "+S 1:1 +A 2"
        document = {"services": services, "networks": {"private": {"external": True, "name": PROJECT + "_private"}},
                    "volumes": {name: {"labels": common["labels"]} for name in ("postgres", "rabbitmq", "objects")}}
        write_atomic(self.compose_file, canonical(document))

    def wait_healthy(self, names, timeout=240):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            ready = True
            for name in names:
                identifier = self.compose("ps", "-q", name, capture=True).decode().strip()
                if not re.fullmatch(r"[a-f0-9]{64}", identifier):
                    ready = False
                    continue
                state = strict_json(run(["docker", "inspect", "--format", "{{json .State}}", identifier], capture=True))
                if not state.get("Running") or state.get("Health", {}).get("Status") != "healthy":
                    ready = False
            if ready:
                return
            time.sleep(3)
        fail("SERVICE_UNHEALTHY")

    def phase_dependencies(self):
        # Never reuse a volume with a matching name unless this job owns its label.
        for name in ("postgres", "rabbitmq", "objects"):
            volume = PROJECT + "_" + name
            try:
                existing = strict_json(run(["docker", "volume", "inspect", "--format", "{{json .}}", volume], capture=True))
            except InstallError:
                existing = None
            if existing is not None and existing.get("Labels", {}).get("io.meetspace.installer") != self.directory.name:
                fail("EXISTING_INSTALLATION")
        self.compose("up", "-d", "--no-build", "--no-deps", "--no-recreate", "postgres", "redis", "rabbitmq", "minio")
        self.wait_healthy(("postgres", "redis", "rabbitmq", "minio"))

    def phase_backup(self):
        target = ROOT / "backups" / (self.directory.name + "-before-migrations.dump")
        receipt = target.with_suffix(".json")
        if target.exists() and receipt.exists():
            if digest(target.read_bytes()) != read_json(receipt)["sha256"]:
                fail("BACKUP_INVALID")
            return
        partial = target.with_suffix(".partial")
        no_symlink(partial)
        command = ["docker", "compose", "-p", PROJECT, "--env-file", str(self.env_file), "-f", str(self.compose_file),
                   "exec", "-T", "postgres", "sh", "-ec", 'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc']
        try:
            descriptor = os.open(partial, os.O_CREAT | os.O_TRUNC | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                process = subprocess.run(command, stdout=stream, stderr=subprocess.DEVNULL, timeout=300, env=SAFE_ENV)
                if process.returncode != 0:
                    fail("BACKUP_FAILED")
                stream.flush()
                os.fsync(stream.fileno())
            if partial.stat().st_size < 100:
                fail("BACKUP_FAILED")
            self.compose("exec", "-T", "postgres", "pg_restore", "--list", data=partial.read_bytes(), code="BACKUP_INVALID")
            os.replace(partial, target)
            write_atomic(receipt, canonical({"schema": 1, "sha256": digest(target.read_bytes()), "jobId": self.directory.name,
                         "beforeMigrations": True, "offsite": False, "restoreDrillPerformed": False}))
        except (OSError, subprocess.TimeoutExpired):
            fail("BACKUP_FAILED")
        finally:
            partial.unlink(missing_ok=True)

    def phase_migrations(self):
        name = "meetspace-migrate-" + self.directory.name
        try:
            current = strict_json(run(["docker", "inspect", "--format", "{{json .State}}", name], capture=True))
        except InstallError:
            current = None
        if current and current.get("Running"):
            run(["docker", "wait", name], timeout=420, code="MIGRATION_IN_PROGRESS")
        # Backend uses a transaction + advisory lock + checksum ledger, so a replay cannot
        # blindly apply a previously committed migration after a lost client checkpoint.
        self.compose("--profile", "migration", "run", "--rm", "--no-deps", "--name", name, "migrate", timeout=420, code="MIGRATION_FAILED")

    def certbot(self, *args):
        image = read_json(self.images_file)["certbot"]
        run(["docker", "run", "--rm", "--network", "host",
             "-v", str(ROOT / "acme") + ":/etc/letsencrypt",
             "-v", str(ROOT / "challenge") + ":/var/www/certbot",
             "-v", "/etc/ssl/certs/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt:ro",
             "-e", "REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt", image, *args], timeout=600, code="ACME_FAILED")

    def copy_certificate(self):
        # Certbot's own live symlinks resolve only into this job's private ACME tree.
        directory = ROOT / "acme" / "live" / self.options["domain"]
        for name in ("fullchain.pem", "privkey.pem"):
            path = (directory / name).resolve(strict=True)
            if not path.is_relative_to((ROOT / "acme" / "archive").resolve()) or not path.is_file() or path.stat().st_size > 64 * 1024:
                fail("CERTIFICATE_INVALID")
            target = ROOT / "tls" / name
            write_atomic(target, path.read_bytes(), 0o644 if name == "fullchain.pem" else 0o640)
            os.chown(target, 0, 1000)
        run(["openssl", "x509", "-in", str(ROOT / "tls" / "fullchain.pem"), "-noout", "-checkhost", self.options["domain"]], code="CERTIFICATE_INVALID")
        run(["openssl", "x509", "-in", str(ROOT / "tls" / "fullchain.pem"), "-noout", "-checkend", "3600"], code="CERTIFICATE_INVALID")

    def phase_https(self):
        self.write_nginx(tls=False)
        self.compose("up", "-d", "--no-build", "--no-deps", "proxy")
        self.compose("exec", "-T", "proxy", "nginx", "-t", code="PROXY_FAILED")
        registration = ["--email", self.options["acmeEmail"]] if self.options["acmeEmail"] else ["--register-unsafely-without-email"]
        self.certbot("certonly", "--non-interactive", "--agree-tos", *registration,
                     "--webroot", "-w", "/var/www/certbot", "--domain", self.options["domain"],
                     "--cert-name", self.options["domain"], "--keep-until-expiring", "--server", self.manifest["acmeDirectory"])
        self.copy_certificate()
        self.write_nginx(tls=True)
        self.compose("exec", "-T", "proxy", "nginx", "-t", code="PROXY_FAILED")
        self.compose("exec", "-T", "proxy", "nginx", "-s", "reload", code="PROXY_FAILED")
        service = "[Unit]\nDescription=Renew MeetSpace TLS certificates\nAfter=docker.service network-online.target\n[Service]\nType=oneshot\nUMask=0077\nExecStart=/usr/bin/python3 /opt/meetspace/installer/v1/runner.py renew " + self.directory.name + "\nStandardOutput=null\nStandardError=null\n"
        timer = "[Unit]\nDescription=MeetSpace TLS renewal schedule\n[Timer]\nOnCalendar=*-*-* 03,15:00:00\nRandomizedDelaySec=3600\nPersistent=true\n[Install]\nWantedBy=timers.target\n"
        for name, content in (("meetspace-tls-renew.service", service), ("meetspace-tls-renew.timer", timer)):
            target = pathlib.Path("/etc/systemd/system") / name
            if target.exists() and target.read_text() != content:
                fail("SYSTEMD_UNIT_CONFLICT")
            write_atomic(target, content.encode(), 0o644)
        run(["systemctl", "daemon-reload"])
        run(["systemctl", "enable", "--now", "meetspace-tls-renew.timer"])

    def phase_services(self):
        self.compose("up", "-d", "--no-build", "--no-deps", "media-worker")
        self.wait_healthy(("media-worker",))
        self.compose("up", "-d", "--no-build", "--no-deps", "api", "worker", "product-worker", "live-worker", "frontend", "coturn")
        self.wait_healthy(("api", "worker", "product-worker", "live-worker", "frontend"))

    def https_json(self, path):
        request = urllib.request.Request("https://" + self.options["domain"] + path, headers={"User-Agent": "MeetSpace-Installer/1"})
        # Do not follow to a different host or silently downgrade HTTPS.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, file, code, message, headers, new_url):
                return None
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=15) as response:
                return strict_json(response.read(MAX_JSON + 1))
        except (OSError, urllib.error.URLError, ValueError):
            fail("HTTPS_READINESS")

    def phase_readiness(self):
        self.wait_healthy(("postgres", "redis", "rabbitmq", "minio", "media-worker", "api", "worker", "product-worker", "live-worker", "frontend"))
        images = read_json(self.images_file)
        document = read_json(self.compose_file)
        for name in document["services"]:
            if name == "migrate":
                continue
            identifier = self.compose("ps", "-q", name, capture=True).decode().strip()
            details = strict_json(run(["docker", "inspect", "--format", "{{json .}}", identifier], capture=True, code="SERVICE_UNHEALTHY"))
            if details.get("Image") != images[name] or not details.get("State", {}).get("Running"):
                fail("IMAGE_CHANGED")
            bindings = details.get("HostConfig", {}).get("PortBindings") or {}
            if name not in ("proxy", "media-worker", "coturn") and bindings:
                fail("PRIVATE_PORT_EXPOSED")
        for path in ("/version", "/version.json"):
            info = self.https_json(path)
            if info.get("version") != self.manifest["version"] or info.get("commit") != self.manifest["commit"]:
                fail("VERSION_MISMATCH")
        # A real TLS WebSocket upgrade without credentials must reach the API and be denied.
        connection = http.client.HTTPSConnection(self.options["domain"], timeout=15, context=ssl.create_default_context())
        try:
            connection.request("GET", "/api/v1/ws", headers={"Upgrade": "websocket", "Connection": "Upgrade",
                "Sec-WebSocket-Key": base64.b64encode(secrets.token_bytes(16)).decode(), "Sec-WebSocket-Version": "13",
                "Origin": "https://" + self.options["domain"]})
            response = connection.getresponse()
            if response.status != 401:
                fail("WSS_AUTH_READINESS")
            response.read(4096)
        except (OSError, http.client.HTTPException):
            fail("WSS_AUTH_READINESS")
        finally:
            connection.close()
        context = ssl.create_default_context()
        try:
            with socket.create_connection((self.options["domain"], 5349), timeout=10) as connection:
                with context.wrap_socket(connection, server_hostname=self.options["domain"]):
                    pass
        except (OSError, ssl.SSLError):
            fail("TURN_TLS_READINESS")
        turn_readiness(self.environment()["TURN_SHARED_SECRET"], self.options["domain"])
        evidence = {"schema": 1, "commit": self.manifest["commit"], "version": self.manifest["version"],
                    "sourceLock": self.plan.get("sourceLock"), "releaseSha256": self.plan["releaseSha256"],
                    "images": images, "verifiedAt": int(time.time()), "profile": self.manifest["profile"],
                    "checks": ["service-health", "image-ids", "private-ports", "https-system-trust", "api-version", "frontend-version", "wss-unauthenticated-denied", "turn-tls", "turn-auth", "turn-private-peer-denied"],
                    "externalCloudFirewallVerified": False, "mediaEndToEndVerified": False,
                    "publicCaIssuance": self.manifest["profile"] == "source-v1"}
        if "imageLock" in self.plan:
            evidence["imageLock"] = self.plan["imageLock"]
        write_atomic(self.directory / "evidence.json", canonical(evidence))

    def execute(self):
        if self.plan["releaseSha256"] != digest(canonical(self.manifest)):
            fail("PLAN_CHANGED")
        completed = read_json(self.directory / "state.json").get("completedPhases", [])
        with lock_file(ROOT / "worker.lock"):
            try:
                for phase in PHASES:
                    if (self.directory / "cancel").exists():
                        state_write(self.directory, "cancelled", phase, completed=completed)
                        return
                    if phase in completed:
                        continue
                    if phase in ("source", "images"):
                        self.check_build_space()
                    elif disk_free() < 2 * 1024 ** 3:
                        fail("DISK_SPACE")
                    state_write(self.directory, "running", phase, completed=completed)
                    getattr(self, "phase_" + phase)()
                    completed.append(phase)
                    state_write(self.directory, "running", phase, completed=completed)
                state_write(self.directory, "completed", "readiness", completed=completed)
            except InstallError as error:
                state_write(self.directory, "cancelled" if error.code == "CANCELLED" else "needs_recovery", phase, None if error.code == "CANCELLED" else error.code, completed=completed)
            except Exception:
                state_write(self.directory, "needs_recovery", phase, "UNEXPECTED_FAILURE", completed=completed)


def turn_readiness(shared_secret, domain):
    """Real TURN Allocate, denial of a private peer, then Refresh lifetime=0."""
    import struct
    import hmac
    cookie = 0x2112A442
    def attribute(kind, value):
        return struct.pack("!HH", kind, len(value)) + value + b"\0" * ((-len(value)) % 4)
    def packet(kind, attributes, key=None):
        transaction = secrets.token_bytes(12)
        body = b"".join(attributes)
        header = struct.pack("!HHI", kind, len(body) + (24 if key else 0), cookie) + transaction
        return header + body + (attribute(0x0008, hmac.new(key, header + body, hashlib.sha1).digest()) if key else b""), transaction
    def exchange(connection, kind, attributes, key=None):
        message, transaction = packet(kind, attributes, key)
        connection.sendto(message, (domain, 3478))
        response = connection.recv(4096)
        if len(response) < 20 or response[8:20] != transaction or response[4:8] != struct.pack("!I", cookie):
            fail("TURN_READINESS")
        response_type, length = struct.unpack("!HH", response[:4])
        if length + 20 != len(response):
            fail("TURN_READINESS")
        fields, offset = {}, 20
        while offset + 4 <= len(response):
            field_type, field_length = struct.unpack("!HH", response[offset:offset + 4])
            if offset + 4 + field_length > len(response):
                fail("TURN_READINESS")
            fields[field_type] = response[offset + 4:offset + 4 + field_length]
            offset += 4 + field_length + (-field_length) % 4
        return response_type, fields
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
            connection.settimeout(5)
            requested = attribute(0x0019, b"\x11\0\0\0")
            kind, fields = exchange(connection, 0x0003, [requested])
            if kind != 0x0113 or fields.get(0x0009, b"")[2:4] != b"\x04\x01":
                fail("TURN_OPEN_RELAY")
            realm, nonce = fields[0x0014], fields[0x0015]
            username = (str(int(time.time()) + 600) + ":meetspace-readiness").encode()
            password = base64.b64encode(hmac.new(shared_secret.encode(), username, hashlib.sha1).digest())
            key = hashlib.md5(username + b":" + realm + b":" + password).digest()
            identity = [attribute(0x0006, username), attribute(0x0014, realm), attribute(0x0015, nonce)]
            kind, fields = exchange(connection, 0x0003, [requested, *identity], key)
            if kind != 0x0103 or 0x0016 not in fields:
                fail("TURN_AUTH_READINESS")
            private = b"\0\x01" + struct.pack("!H", 9 ^ (cookie >> 16)) + struct.pack("!I", int(ipaddress.IPv4Address("127.0.0.1")) ^ cookie)
            kind, fields = exchange(connection, 0x0008, [attribute(0x0012, private), *identity], key)
            denied = kind == 0x0118 and fields.get(0x0009, b"")[2:4] == b"\x04\x03"
            exchange(connection, 0x0004, [attribute(0x000D, b"\0\0\0\0"), *identity], key)
            if not denied:
                fail("TURN_PRIVATE_PEER_ALLOWED")
    except (OSError, KeyError, ValueError, struct.error):
        fail("TURN_READINESS")


def renew(value, manifest):
    worker = Worker(value, manifest)
    if status(value)["state"] != "completed":
        fail("INSTALLATION_INCOMPLETE")
    with lock_file(ROOT / "worker.lock"):
        previous = digest((ROOT / "tls" / "fullchain.pem").read_bytes())
        worker.certbot("renew", "--non-interactive", "--quiet")
        worker.copy_certificate()
        if digest((ROOT / "tls" / "fullchain.pem").read_bytes()) != previous:
            worker.compose("exec", "-T", "proxy", "nginx", "-t", code="PROXY_FAILED")
            worker.compose("exec", "-T", "proxy", "nginx", "-s", "reload", code="PROXY_FAILED")
            worker.compose("restart", "coturn")


def main(action, request):
    if os.geteuid() != 0:
        fail("ROOT_REQUIRED")
    os.umask(0o077)
    if action != "apply" and isinstance(request, dict) and "smtpPassword" in request:
        fail("INVALID_REQUEST")
    manifest, bundle = load_bundle()
    if action == "plan":
        return plan_latest(request.get("options"), manifest)
    if action == "apply":
        return apply(request, manifest, bundle)
    if action in ("status", "cancel", "resume"):
        return control(action, request, manifest)
    if action == "worker":
        Worker(job_id(request), manifest).execute()
        return {"finished": True}
    if action == "renew":
        renew(job_id(request), manifest)
        return {"renewed": True}
    fail("INVALID_REQUEST")


def entry(action, request):
    try:
        result = main(action, request)
        response = {"protocol": "MSI1", "ok": True, "result": result}
    except InstallError as error:
        response = {"protocol": "MSI1", "ok": False, "errorCode": error.code}
    except Exception:
        response = {"protocol": "MSI1", "ok": False, "errorCode": "UNEXPECTED_FAILURE"}
    print(canonical(response).decode(), flush=True)
    return response["ok"]


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else ""
    if action in ("worker", "renew"):
        request = sys.argv[2] if len(sys.argv) == 3 else ""
    else:
        try:
            request = strict_json(sys.stdin.buffer.read(MAX_JSON + 1))
        except InstallError:
            print(canonical({"protocol": "MSI1", "ok": False, "errorCode": "INVALID_REQUEST"}).decode())
            sys.exit(1)
    sys.exit(0 if entry(action, request) else 1)
