#!/usr/bin/env python3
"""MeetSpace source installer v1. JSON control protocol, no credentials in output.

The SSH bootstrap supplies trusted source/key/manifest bytes from the signed APK.
After apply the same files live in a root-owned directory. Never executes config.
"""
import base64
import contextlib
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
SAFE_ENV = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "HOME": "/root", "DEBIAN_FRONTEND": "noninteractive", "GIT_TERMINAL_PROMPT": "0"}


class InstallError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def fail(code):
    raise InstallError(code)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(raw, maximum=MAX_JSON):
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
        if not isinstance(value, dict):
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


def validate_options(options, manifest):
    if not isinstance(options, dict) or set(options) != {"domain", "publicIpv4", "acmeEmail"}:
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
    email = options["acmeEmail"]
    if not isinstance(email, str) or len(email) > 254 or not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}", email):
        fail("INVALID_EMAIL")
    return {"domain": domain, "publicIpv4": str(ip), "acmeEmail": email}


def platform_facts():
    values = {}
    for line in pathlib.Path("/etc/os-release").read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"')
    arch = {"aarch64": "arm64", "x86_64": "amd64"}.get(os.uname().machine, "unsupported")
    if values.get("ID") != "ubuntu" or values.get("VERSION_ID") != "24.04" or arch == "unsupported":
        fail("UNSUPPORTED_PLATFORM")
    if not pathlib.Path("/run/systemd/system").is_dir() or not shutil.which("systemd-run"):
        fail("SYSTEMD_REQUIRED")
    memory = int(re.search(r"^MemTotal:\s+(\d+)", pathlib.Path("/proc/meminfo").read_text(), re.M).group(1))
    return {"os": "ubuntu", "osVersion": "24.04", "arch": arch, "cpus": os.cpu_count() or 0, "memoryKiB": memory}


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


def docker_facts():
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
    compose_version = run(["docker", "compose", "version", "--short"], capture=True, code="COMPOSE_REQUIRED").decode().strip()
    if not re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", version) or not re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", compose_version):
        fail("DOCKER_UNSUPPORTED")
    if int(version.lstrip("v").split(".")[0]) < 24 or int(compose_version.lstrip("v").split(".")[0]) < 2:
        fail("DOCKER_UNSUPPORTED")
    return {"installed": True, "version": version, "composeVersion": compose_version}


def create_plan(options, manifest, *, issued_at=None):
    options = validate_options(options, manifest)
    facts = platform_facts()
    if facts["cpus"] < 4 or facts["memoryKiB"] < 7 * 1024 * 1024:
        fail("RESOURCES_INSUFFICIENT")
    marker = managed_marker()
    if disk_free() < (2 if marker else 30) * 1024 ** 3:
        fail("DISK_SPACE")
    docker = docker_facts()
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
            "backendTag": None, "releaseSha256": digest(canonical(manifest)), "runnerSha256": manifest["runnerSha256"],
            "options": options, "platform": facts, "root": str(ROOT), "project": PROJECT,
            "docker": docker, "dockerPackages": manifest["dockerPackages"][facts["arch"]],
            "dnsAddresses": dns, "existingJobId": marker.get("jobId") if marker else None,
            "existingPlanHash": read_json(job_directory(marker["jobId"]) / "plan.json")["hash"] if marker else None,
            "issuedAt": created, "expiresAt": created + 900,
            "actions": ["install-docker" if not docker["installed"] else "reuse-docker", "fetch-verified-source", "build-and-pin-images", "generate-secrets", "private-volumes", "backup-and-migrate", "acme-https-wss", "authenticated-turn", "readiness"],
            "ports": {"tcp": PORTS["tcp"], "udp": ["3478", "50010", "49160-49259"]},
            "warnings": ["source-build-not-hermetic", "docker-firewall-bypass", "external-ports-must-be-open", "no-automatic-data-rollback", "local-backup-not-offsite", "no-upstream-release-tag", "turn-restarts-on-certificate-renewal"]}
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


def initialize_layout(value, plan, manifest, bundle):
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
        write_atomic(directory / "plan.json", canonical(plan))
        state_write(directory, "running", "queued")
        write_atomic(prepared / "managed.json", canonical({"schema": 1, "owner": "meetspace-installer-v1", "jobId": value,
                     "domain": plan["options"]["domain"], "commit": manifest["commit"], "releaseSha256": digest(canonical(manifest))}))
        # File writes fsync their direct parent. Persist every newly created directory
        # entry as well before making the complete tree visible at its final location.
        for folder in (directory, prepared / "jobs", prepared / "installer" / "v1", prepared / "installer", prepared):
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
        fresh = create_plan(plan.get("options"), manifest, issued_at=plan["issuedAt"])
        if fresh["hash"] != plan["hash"]:
            fail("PLAN_CHANGED")
        if request.get("acceptAcmeTerms") is not True:
            fail("ACME_TERMS_REQUIRED")
        if not fresh["docker"]["installed"] and request.get("confirmDockerInstall") is not True:
            fail("DOCKER_CONFIRMATION_REQUIRED")
        initialize_layout(value, plan, manifest, bundle)
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


def pinned_dockerfile(content, bases):
    lines, stages = [], set()
    for line in content.splitlines():
        if line.startswith("FROM "):
            fields = line.split()
            index = 2 if fields[1].startswith("--platform=") else 1
            image = fields[index]
            if image not in stages and image != "scratch":
                if image not in bases:
                    fail("UNPINNED_BASE_IMAGE")
                fields[index] = bases[image]
            if "AS" in fields:
                stages.add(fields[fields.index("AS") + 1])
            line = " ".join(fields)
        lines.append(line)
    return "\n".join(lines) + "\n"


class Worker:
    def __init__(self, value, manifest):
        self.directory = job_directory(value)
        self.plan = read_json(self.directory / "plan.json")
        self.manifest = manifest
        self.options = self.plan["options"]
        self.release = ROOT / "releases" / manifest["commit"]
        self.config = ROOT / "config"
        self.compose_file = self.config / "compose.json"
        self.images_file = self.directory / "images.json"
        self.env_file = self.config / "server.env"

    def compose(self, *args, capture=False, timeout=300, code="COMPOSE_FAILED", data=None):
        return run(["docker", "compose", "--project-name", PROJECT, "--project-directory", str(self.release),
                    "--env-file", str(self.env_file), "-f", str(self.compose_file), *args],
                   capture=capture, timeout=timeout, code=code, data=data)

    def phase_docker(self):
        if docker_facts()["installed"]:
            return
        key_target = pathlib.Path("/etc/apt/keyrings/meetspace-docker.asc")
        source_target = pathlib.Path("/etc/apt/sources.list.d/meetspace-docker.sources")
        key_target.parent.mkdir(mode=0o755, exist_ok=True)
        os.chmod(key_target.parent, 0o755)
        key = (ROOT / "installer" / "v1" / "docker.asc").read_bytes()
        if digest(key) != self.manifest["dockerKeySha256"]:
            fail("RELEASE_CHECKSUM")
        sources = ("Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: noble\nComponents: stable\n"
                   "Architectures: " + self.plan["platform"]["arch"] + "\nSigned-By: " + str(key_target) + "\n").encode()
        for path, data in ((key_target, key), (source_target, sources)):
            if path.exists() and path.read_bytes() != data:
                fail("APT_SOURCE_CONFLICT")
            write_atomic(path, data, 0o644)
        run(["apt-get", "-o", "DPkg::Lock::Timeout=120", "update", "-qq"], timeout=600, code="DOCKER_INSTALL_FAILED")
        packages = [key + "=" + value for key, value in sorted(self.plan["dockerPackages"].items())]
        run(["apt-get", "-o", "DPkg::Lock::Timeout=120", "install", "-y", "--no-install-recommends", *packages], timeout=900, code="DOCKER_INSTALL_FAILED")
        run(["systemctl", "enable", "--now", "docker"], timeout=90, code="DOCKER_INSTALL_FAILED")
        docker_facts()

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

    def phase_images(self):
        images = read_json(self.images_file) if self.images_file.exists() else {}
        for service, reference in self.manifest["images"].items():
            self.check_cancel()
            run(["docker", "pull", "--platform", "linux/" + self.plan["platform"]["arch"], reference], timeout=900, code="IMAGE_PULL_FAILED")
            details = self.image_details(reference)
            if service in images and images[service] != details["Id"]:
                fail("IMAGE_CHANGED")
            images[service] = details["Id"]
            write_atomic(self.images_file, canonical(images))
        for service in APP_SERVICES:
            self.check_cancel()
            recipe = self.manifest["dockerfiles"][service]
            dockerfile = self.release / recipe["path"]
            if digest(dockerfile.read_bytes()) != recipe["sha256"]:
                fail("SOURCE_INVALID")
            if service in images:
                details = self.image_details(images[service])
                labels = details.get("Config", {}).get("Labels", {}) or {}
                if labels.get("io.meetspace.recipe") != self.plan["releaseSha256"] or labels.get("org.opencontainers.image.revision") != self.manifest["commit"]:
                    fail("IMAGE_CHANGED")
                continue
            prepared = self.directory / (service + ".Dockerfile")
            write_atomic(prepared, pinned_dockerfile(dockerfile.read_text(), self.manifest["baseImages"]).encode())
            context = self.release / recipe["context"]
            tag = "meetspace-installer/" + service + ":" + self.manifest["commit"][:12] + "-" + self.plan["releaseSha256"][:12]
            # Docker output may contain build/dependency data: discard, expose fixed phase events only.
            run(["docker", "build", "--platform", "linux/" + self.plan["platform"]["arch"], "--target", "runtime", "--file", str(prepared),
                 "--tag", tag, "--build-arg", "BUILD_VERSION=" + self.manifest["version"], "--build-arg", "BUILD_COMMIT=" + self.manifest["commit"],
                 "--build-arg", "BUILD_TIME=" + self.manifest["builtAt"], "--build-arg", "TARGETARCH=" + self.plan["platform"]["arch"],
                 "--build-arg", "CLIENT_TELEMETRY_ENABLED=false", "--label", "org.opencontainers.image.revision=" + self.manifest["commit"],
                 "--label", "io.meetspace.recipe=" + self.plan["releaseSha256"], str(context)], timeout=3600, code="IMAGE_BUILD_FAILED")
            details = self.image_details(tag)
            images[service] = details["Id"]
            write_atomic(self.images_file, canonical(images))
            state_write(self.directory, "running", "images:" + service)

    def environment(self):
        out = {}
        for line in self.env_file.read_text().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                out[key] = value
        return out

    def phase_configuration(self):
        self.config.mkdir(mode=0o700, parents=True, exist_ok=True)
        for folder in ("recordings", "tls", "acme", "challenge", "backups"):
            path = ROOT / folder
            path.mkdir(mode=0o755 if folder in ("tls", "challenge") else 0o700, exist_ok=True)
            if folder in ("tls", "challenge"):
                os.chmod(path, 0o755)
            if folder == "recordings":
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
                "DB_MAX_OPEN": "10", "DB_MAX_IDLE": "5", "MEDIA_MAX_ROOMS": "10", "MEDIA_MAX_PEERS": "10"})
            write_atomic(self.env_file, ("\n".join(key + "=" + value for key, value in sorted(values.items())) + "\n").encode())
        values = self.environment()
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
        self.certbot("certonly", "--non-interactive", "--agree-tos", "--email", self.options["acmeEmail"],
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
                    "images": images, "verifiedAt": int(time.time()), "profile": self.manifest["profile"],
                    "checks": ["service-health", "image-ids", "private-ports", "https-system-trust", "api-version", "frontend-version", "wss-unauthenticated-denied", "turn-tls", "turn-auth", "turn-private-peer-denied"],
                    "externalCloudFirewallVerified": False, "mediaEndToEndVerified": False,
                    "publicCaIssuance": self.manifest["profile"] == "source-v1"}
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
                    if disk_free() < (2 * 1024 ** 3 if phase not in ("source", "images") else 8 * 1024 ** 3):
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
    manifest, bundle = load_bundle()
    if action == "plan":
        return create_plan(request.get("options"), manifest)
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
