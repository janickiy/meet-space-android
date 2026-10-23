"""Resource plans freeze the reviewed budget and constrain actual build stages."""
import contextlib
import copy
import json
import pathlib
import shlex
import tempfile
import unittest
import uuid
from unittest.mock import patch

from test_runner import ROOT, r


GIB = 1024 ** 3
GIB_KIB = 1024 ** 2


class ResourceProfileTests(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ROOT / "installer/v1/release.json").read_bytes())
        self.options = {"domain": "meet.example.com", "publicIpv4": "8.8.8.8", "acmeEmail": ""}

    def rejected(self, code, function, *args, **kwargs):
        with self.assertRaises(r.InstallError) as caught:
            function(*args, **kwargs)
        self.assertEqual(code, caught.exception.code)

    def create(self, *, cpus=4, memory=7 * GIB_KIB, free=30 * GIB, existing=False, **kwargs):
        facts = {"os": "ubuntu", "osVersion": "24.04", "arch": "amd64", "cpus": cpus, "memoryKiB": memory}
        marker = {"commit": self.manifest["commit"], "domain": self.options["domain"],
                  "releaseSha256": r.digest(r.canonical(self.manifest)), "jobId": "00000000-0000-4000-8000-000000000001"} if existing else None
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(r, "platform_facts", return_value=facts))
            stack.enter_context(patch.object(r, "managed_marker", return_value=marker))
            stack.enter_context(patch.object(r, "disk_free", return_value=free))
            stack.enter_context(patch.object(r, "docker_facts", return_value={"installed": False, "version": None, "composeVersion": None}))
            stack.enter_context(patch.object(r, "check_ports"))
            stack.enter_context(patch.object(r, "dns_check", return_value=["8.8.8.8"]))
            stack.enter_context(patch.object(r.pathlib.Path, "exists", return_value=False))
            if existing:
                stack.enter_context(patch.object(r, "job_directory", return_value=pathlib.Path("/unused/job")))
                stack.enter_context(patch.object(r, "read_json", return_value={"hash": "a" * 64}))
            return r.create_plan(self.options, self.manifest, issued_at=123, **kwargs)

    def test_auto_profile_selects_each_cpu_memory_and_disk_boundary(self):
        cases = [
            (4, 7 * GIB_KIB, 30 * GIB, "standard"),
            (8, 16 * GIB_KIB, 40 * GIB, "standard"),
            (2, 3 * GIB_KIB, 18 * GIB, "compact-test"),
            (2, 3_840_000, 20 * GIB, "compact-test"),
            (3, 7 * GIB_KIB, 30 * GIB, "compact-test"),
            (4, 7 * GIB_KIB - 1, 30 * GIB, "compact-test"),
            (4, 7 * GIB_KIB, 30 * GIB - 1, "compact-test"),
        ]
        for cpus, memory, free, expected in cases:
            with self.subTest(cpus=cpus, memory=memory, free=free):
                plan = self.create(cpus=cpus, memory=memory, free=free)
                self.assertEqual(expected, plan["resourceProfile"])
                self.assertEqual(expected == "compact-test", "compact-test-resources" in plan["warnings"])
                self.assertEqual(plan["hash"], r.digest(r.canonical({key: value for key, value in plan.items() if key != "hash"})))

    def test_below_compact_minimum_still_rejects_before_host_mutations(self):
        for overrides, expected in [
            ({"cpus": 1}, "RESOURCES_INSUFFICIENT"),
            ({"memory": 3 * GIB_KIB - 1}, "RESOURCES_INSUFFICIENT"),
            ({"free": 18 * GIB - 1}, "DISK_SPACE"),
        ]:
            with self.subTest(overrides=overrides), patch.object(r, "run") as execute:
                self.rejected(expected, self.create, **overrides)
                execute.assert_not_called()

    def test_explicit_standard_profile_does_not_fall_back_to_compact(self):
        for overrides, expected in [
            ({"cpus": 2}, "RESOURCES_INSUFFICIENT"),
            ({"memory": 7 * GIB_KIB - 1}, "RESOURCES_INSUFFICIENT"),
            ({"free": 30 * GIB - 1}, "DISK_SPACE"),
        ]:
            with self.subTest(overrides=overrides):
                self.rejected(expected, self.create, resource_profile="standard", **overrides)

    def test_existing_installation_uses_two_gibibyte_disk_boundary(self):
        for profile, cpus, memory in (("standard", 4, 7 * GIB_KIB), ("compact-test", 2, 3 * GIB_KIB)):
            with self.subTest(profile=profile):
                plan = self.create(cpus=cpus, memory=memory, free=2 * GIB, existing=True, resource_profile=profile)
                self.assertEqual(profile, plan["resourceProfile"])
                self.assertEqual("a" * 64, plan["existingPlanHash"])
                self.rejected("DISK_SPACE", self.create, cpus=cpus, memory=memory, free=2 * GIB - 1,
                              existing=True, resource_profile=profile)

    def test_frozen_compact_profile_is_not_upgraded_and_is_bound_by_hash(self):
        standard = self.create()
        compact = self.create(resource_profile="compact-test")
        self.assertEqual("compact-test", compact["resourceProfile"])
        self.assertNotEqual(standard["hash"], compact["hash"])
        self.assertEqual(compact, self.create(resource_profile="compact-test"))
        tampered = copy.deepcopy(compact)
        tampered["resourceProfile"] = "standard"
        self.rejected("PLAN_CHANGED", r.apply, {"plan": tampered, "confirmedPlanHash": compact["hash"]}, self.manifest, {})

    def test_legacy_plan_has_original_shape_and_standard_requirements(self):
        plan = self.create(legacy_plan=True)
        self.assertNotIn("resourceProfile", plan)
        self.assertNotIn("compact-test-resources", plan["warnings"])
        self.assertEqual("standard", r.plan_resource_profile(plan))
        self.rejected("RESOURCES_INSUFFICIENT", self.create, cpus=2, legacy_plan=True)
        self.rejected("DISK_SPACE", self.create, free=30 * GIB - 1, legacy_plan=True)

    def test_profile_reader_rejects_unknown_or_malformed_present_values(self):
        self.assertEqual("standard", r.plan_resource_profile({}))
        for profile in ("standard", "compact-test"):
            with self.subTest(profile=profile):
                self.assertEqual(profile, r.plan_resource_profile({"resourceProfile": profile}))
        for profile in (None, "", "compact", "auto", "STANDARD", False, 1, [], {}):
            with self.subTest(profile=profile):
                self.rejected("PLAN_CHANGED", r.plan_resource_profile, {"resourceProfile": profile})

    def test_apply_revalidates_the_reviewed_profile_and_legacy_shape(self):
        for legacy in (False, True):
            with self.subTest(legacy=legacy):
                plan = self.create(legacy_plan=True) if legacy else self.create(resource_profile="compact-test")
                request = {"plan": plan, "confirmedPlanHash": plan["hash"], "jobId": str(uuid.uuid4()),
                           "acceptAcmeTerms": True, "confirmDockerInstall": True}
                with contextlib.ExitStack() as stack:
                    stack.enter_context(patch.object(r, "lock_file", return_value=contextlib.nullcontext()))
                    stack.enter_context(patch.object(r, "managed_marker", return_value=None))
                    stack.enter_context(patch.object(r.time, "time", return_value=124))
                    fresh = stack.enter_context(patch.object(r, "create_plan", return_value=plan))
                    stack.enter_context(patch.object(r, "initialize_layout"))
                    stack.enter_context(patch.object(r, "job_directory", return_value=pathlib.Path("/unused/job")))
                    stack.enter_context(patch.object(r, "start_unit"))
                    stack.enter_context(patch.object(r, "status", return_value={"state": "running"}))
                    self.assertEqual({"state": "running"}, r.apply(request, self.manifest, {}))
                self.assertEqual("standard" if legacy else "compact-test", fresh.call_args.kwargs["resource_profile"])
                self.assertEqual(legacy, fresh.call_args.kwargs["legacy_plan"])

    def test_existing_job_plan_keeps_its_profile_without_new_source_lookup(self):
        for legacy in (False, True):
            with self.subTest(legacy=legacy):
                plan = self.create(legacy_plan=True) if legacy else self.create(resource_profile="compact-test")
                with contextlib.ExitStack() as stack:
                    stack.enter_context(patch.object(r, "managed_marker", return_value={"jobId": str(uuid.uuid4())}))
                    stack.enter_context(patch.object(r, "job_directory", return_value=pathlib.Path("/unused/job")))
                    stack.enter_context(patch.object(r, "read_json", return_value=plan))
                    fresh = stack.enter_context(patch.object(r, "create_plan", return_value=plan))
                    lookup = stack.enter_context(patch.object(r, "resolve_source", side_effect=AssertionError("must retain source")))
                    self.assertEqual(plan, r.plan_latest(self.options, self.manifest))
                lookup.assert_not_called()
                self.assertEqual("standard" if legacy else "compact-test", fresh.call_args.kwargs["resource_profile"])

    def test_worker_reads_only_frozen_plan_and_old_workers_default_standard(self):
        worker = object.__new__(r.Worker)
        self.assertFalse(worker.compact)
        worker.plan = {}
        self.assertFalse(worker.compact)
        worker.plan = {"resourceProfile": "compact-test"}
        with patch.object(r, "platform_facts", side_effect=AssertionError("must use the reviewed plan")):
            self.assertTrue(worker.compact)
        worker.plan = {"resourceProfile": "standard"}
        self.assertFalse(worker.compact)


class BuildResourceTests(unittest.TestCase):
    def setUp(self):
        self.bases = {name: name.split(":", 1)[0] + "@sha256:" + char * 64 for name, char in
                      (("golang:1.26-alpine", "a"), ("node:24-alpine", "b"), ("alpine:3.22", "c"), ("nginx:alpine", "d"))}

    def stage_environment(self, source):
        environment = {}
        for line in source.splitlines():
            if line.lstrip().upper().startswith("RUN "):
                break
            if line.lstrip().upper().startswith("ENV "):
                for assignment in shlex.split(line)[1:]:
                    if "=" in assignment:
                        key, value = assignment.split("=", 1)
                        environment[key] = value
        return environment

    def test_go_budget_is_effective_before_dependency_download_and_build(self):
        source = "FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS build\nWORKDIR /src\nRUN go mod download\nRUN go build ./cmd/api\nFROM alpine:3.22 AS runtime\nRUN apk add --no-cache ca-certificates\n"
        prepared = r.pinned_dockerfile(source, self.bases, resource_profile="compact-test")
        build, runtime = prepared.split("FROM " + self.bases["alpine:3.22"], 1)
        self.assertEqual({"GOMAXPROCS": "2", "GOFLAGS": "-p=1", "GOMEMLIMIT": "1536MiB"}, self.stage_environment(build))
        self.assertNotIn("GOMAXPROCS", runtime)
        self.assertNotIn("NODE_OPTIONS", prepared)
        self.assertIn(" go mod download\n", prepared)
        self.assertIn(" go build ./cmd/api", prepared)
        self.assertIn("target=/go/pkg/mod,sharing=locked", prepared)
        self.assertNotIn("golang:1.26-alpine", prepared)

    def test_node_budget_is_effective_for_install_and_frontend_build(self):
        source = "FROM node:24-alpine AS build\nWORKDIR /app\nRUN npm ci\nRUN npm run build\nFROM nginx:alpine AS runtime\nRUN apk upgrade --no-cache\n"
        prepared = r.pinned_dockerfile(source, self.bases, resource_profile="compact-test")
        build, runtime = prepared.split("FROM " + self.bases["nginx:alpine"], 1)
        environment = self.stage_environment(build)
        self.assertEqual("--max-old-space-size=1536", environment["NODE_OPTIONS"])
        self.assertEqual("2", environment["UV_THREADPOOL_SIZE"])
        self.assertNotIn("NODE_OPTIONS", runtime)
        self.assertNotIn("GOFLAGS", prepared)
        self.assertIn("target=/root/.npm,sharing=locked npm ci\n", prepared)
        self.assertIn("RUN npm run build", prepared)

    def test_standard_and_legacy_default_do_not_change_build_budget(self):
        source = "FROM golang:1.26-alpine AS build\nRUN go build ./cmd/api\nFROM build AS runtime\n"
        expected = r.pinned_dockerfile(source, self.bases)
        self.assertIn("FROM " + self.bases["golang:1.26-alpine"], expected)
        self.assertIn(" go build ./cmd/api\nFROM build AS runtime\n", expected)
        self.assertNotIn("GOMAXPROCS", expected)
        self.assertNotIn("GOFLAGS", expected)
        self.assertEqual(expected, r.pinned_dockerfile(source, self.bases))
        self.assertEqual(expected, r.pinned_dockerfile(source, self.bases, resource_profile="standard"))

    def test_mixed_case_from_and_alias_keep_pinning_and_build_limits(self):
        source = "from --platform=$BUILDPLATFORM golang:1.26-alpine as build\nRUN go mod download\nFrOm build aS copied\nFROM alpine:3.22 AS runtime\n"
        prepared = r.pinned_dockerfile(source, self.bases, resource_profile="compact-test")
        self.assertIn(self.bases["golang:1.26-alpine"], prepared)
        self.assertIn(self.bases["alpine:3.22"], prepared)
        self.assertEqual("2", self.stage_environment(prepared)["GOMAXPROCS"])
        self.assertNotIn("golang:1.26-alpine", prepared)
        with self.assertRaises(r.InstallError) as caught:
            r.pinned_dockerfile("from unknown:latest as build\n", self.bases, resource_profile="compact-test")
        self.assertEqual("UNPINNED_BASE_IMAGE", caught.exception.code)

    def test_non_language_runtime_images_do_not_receive_language_limits(self):
        source = "FROM nginx:alpine AS runtime\nRUN apk upgrade --no-cache\n"
        prepared = r.pinned_dockerfile(source, self.bases, resource_profile="compact-test")
        self.assertEqual("FROM " + self.bases["nginx:alpine"] + " AS runtime\nRUN apk upgrade --no-cache\n", prepared)


class ImagePhaseResourceTests(unittest.TestCase):
    @contextlib.contextmanager
    def fixture(self, profile):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, "no_symlink"), patch.object(r, "state_write"):
            root = pathlib.Path(directory)
            worker = object.__new__(r.Worker)
            worker.directory = root / "job"
            worker.directory.mkdir()
            worker.release = root / "source"
            worker.release.mkdir()
            worker.images_file = worker.directory / "images.json"
            worker.plan = {"resourceProfile": profile, "platform": {"arch": "amd64"}, "releaseSha256": "a" * 64}
            worker.manifest = {"images": {"postgres": "postgres@sha256:" + "b" * 64, "redis": "redis@sha256:" + "c" * 64},
                               "commit": "d" * 40, "version": "0.3.0", "builtAt": "2026-10-17T00:00:00Z",
                               "baseImages": {"golang:1.26-alpine": "golang@sha256:" + "e" * 64,
                                              "node:24-alpine": "node@sha256:" + "f" * 64,
                                              "alpine:3.22": "alpine@sha256:" + "1" * 64}, "dockerfiles": {}}
            sources = {}
            for service in r.APP_SERVICES:
                context = "frontend" if service == "frontend" else "."
                path = ("frontend" if service == "frontend" else "dockers/" + service) + "/Dockerfile"
                if service == "frontend":
                    content = b"FROM node:24-alpine AS build\nRUN npm ci\nRUN npm run build\nFROM alpine:3.22 AS runtime\n"
                elif service == "minio":
                    content = b"FROM alpine:3.22 AS runtime\nRUN apk upgrade --no-cache\n"
                else:
                    content = b"FROM golang:1.26-alpine AS build\nRUN go mod download\nRUN go build ./cmd/app\nFROM alpine:3.22 AS runtime\n"
                target = worker.release / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                sources[target] = content
                worker.manifest["dockerfiles"][service] = {"path": path, "context": context, "sha256": r.digest(content)}
            yield worker, sources

    def details(self, reference):
        return {"Id": "sha256:" + r.digest(reference.encode()), "Architecture": "amd64", "Config": {"Labels": {}}}

    def test_build_commands_use_profile_tags_labels_and_effective_generated_dockerfiles(self):
        for profile in ("standard", "compact-test"):
            with self.subTest(profile=profile), self.fixture(profile) as (worker, sources):
                events = []
                commands = []

                def available():
                    events.append("disk")
                    return 40 * GIB

                def execute(argv, **kwargs):
                    events.append(argv[1])
                    commands.append((list(argv), kwargs))
                    if argv[1] == "build":
                        prepared = pathlib.Path(argv[argv.index("--file") + 1])
                        content = prepared.read_text()
                        self.assertEqual(worker.directory, prepared.parent)
                        if prepared.stem == "frontend":
                            expected = "NODE_OPTIONS=--max-old-space-size=1536"
                        elif prepared.stem == "minio":
                            expected = None
                        else:
                            expected = "GOFLAGS=-p=1"
                        if expected and profile == "compact-test":
                            self.assertLess(content.index(expected), content.index("RUN "))
                        else:
                            self.assertNotIn("NODE_OPTIONS", content)
                            self.assertNotIn("GOFLAGS", content)
                    return b""

                with patch.object(r, "disk_free", side_effect=available), patch.object(r, "run", side_effect=execute), \
                     patch.object(worker, "image_details", side_effect=self.details):
                    worker.phase_images()

                self.assertEqual(["disk", "pull"] * len(worker.manifest["images"]) + ["disk", "build"] * len(r.APP_SERVICES), events)
                builds = [(argv, kwargs) for argv, kwargs in commands if argv[1] == "build"]
                self.assertEqual(list(r.APP_SERVICES), [pathlib.Path(argv[argv.index("--file") + 1]).stem for argv, _ in builds])
                for argv, kwargs in builds:
                    service = pathlib.Path(argv[argv.index("--file") + 1]).stem
                    expected_tag = "meetspace-installer/" + service + ":" + worker.manifest["commit"][:12] + "-" + worker.plan["releaseSha256"][:12]
                    if profile == "compact-test":
                        expected_tag += "-compact-test"
                    self.assertEqual(expected_tag, argv[argv.index("--tag") + 1])
                    labels = [argv[index + 1] for index, item in enumerate(argv) if item == "--label"]
                    self.assertIn("io.meetspace.resource-profile=" + profile, labels)
                    self.assertIn("io.meetspace.recipe=" + worker.plan["releaseSha256"], labels)
                    self.assertIn("org.opencontainers.image.revision=" + worker.manifest["commit"], labels)
                    self.assertEqual("runtime", argv[argv.index("--target") + 1])
                    self.assertEqual("linux/amd64", argv[argv.index("--platform") + 1])
                    self.assertEqual(str(worker.release / worker.manifest["dockerfiles"][service]["context"]), argv[-1])
                    self.assertEqual(7200 if profile == "compact-test" else 3600, kwargs["timeout"])
                self.assertTrue(all("prune" not in argv for argv, _ in commands))
                self.assertEqual(set(worker.manifest["images"]) | set(r.APP_SERVICES), set(r.read_json(worker.images_file)))
                for path, original in sources.items():
                    self.assertEqual(original, path.read_bytes())

    def test_each_profile_requires_its_build_disk_reserve(self):
        for profile, reserve in (("standard", 8 * GIB), ("compact-test", 6 * GIB)):
            with self.subTest(profile=profile):
                worker = object.__new__(r.Worker)
                worker.plan = {"resourceProfile": profile}
                with patch.object(r, "disk_free", return_value=reserve):
                    self.assertIsNone(worker.check_build_space())
                with patch.object(r, "disk_free", return_value=reserve - 1):
                    with self.assertRaises(r.InstallError) as caught:
                        worker.check_build_space()
                self.assertEqual("DISK_SPACE", caught.exception.code)

    def test_build_time_is_utc_without_changing_frozen_manifest(self):
        for profile in ("standard", "compact-test"):
            with self.subTest(profile=profile), self.fixture(profile) as (worker, _):
                worker.manifest["builtAt"] = "2026-10-22T20:41:14+03:00"
                original = r.canonical(worker.manifest)
                with patch.object(r, "disk_free", return_value=40 * GIB), \
                     patch.object(r, "run", return_value=b"") as execute, \
                     patch.object(worker, "image_details", side_effect=self.details):
                    worker.phase_images()
                builds = [call.args[0] for call in execute.call_args_list if call.args[0][:2] == ["docker", "build"]]
                self.assertEqual(len(r.APP_SERVICES), len(builds))
                for argv in builds:
                    self.assertIn("BUILD_TIME=2026-10-22T17:41:14Z", argv)
                self.assertEqual(original, r.canonical(worker.manifest))

    def test_invalid_build_time_stops_before_pulling_or_building(self):
        with self.fixture("compact-test") as (worker, _), patch.object(r, "run") as execute:
            worker.manifest["builtAt"] = "2026-10-22T20:41:14"
            with self.assertRaises(r.InstallError) as caught:
                worker.phase_images()
            self.assertEqual("RELEASE_INVALID", caught.exception.code)
            execute.assert_not_called()

    def test_low_disk_stops_before_next_pull_or_build_without_pruning(self):
        for operation in ("pull", "build"):
            with self.subTest(operation=operation), self.fixture("compact-test") as (worker, _):
                if operation == "build":
                    worker.manifest["images"] = {}
                with patch.object(r, "disk_free", side_effect=[40 * GIB, 6 * GIB - 1]), \
                     patch.object(r, "run", return_value=b"") as execute, \
                     patch.object(worker, "image_details", side_effect=self.details):
                    with self.assertRaises(r.InstallError) as caught:
                        worker.phase_images()
                self.assertEqual("DISK_SPACE", caught.exception.code)
                execute.assert_called_once()
                self.assertEqual(["docker", operation], execute.call_args.args[0][:2])

    def test_cached_image_from_another_profile_cannot_be_reused(self):
        for profile, cached_profile in (("compact-test", "standard"), ("standard", "compact-test"), ("compact-test", None)):
            with self.subTest(profile=profile, cached_profile=cached_profile), self.fixture(profile) as (worker, _):
                worker.manifest["images"] = {}
                identifier = "sha256:" + "a" * 64
                worker.images_file.write_bytes(r.canonical({"api": identifier}))
                labels = {"io.meetspace.recipe": worker.plan["releaseSha256"], "org.opencontainers.image.revision": worker.manifest["commit"]}
                if cached_profile is not None:
                    labels["io.meetspace.resource-profile"] = cached_profile
                with patch.object(r, "disk_free", return_value=40 * GIB), patch.object(r, "run") as execute, \
                     patch.object(worker, "image_details", return_value={"Id": identifier, "Config": {"Labels": labels}}):
                    with self.assertRaises(r.InstallError) as caught:
                        worker.phase_images()
                self.assertEqual("IMAGE_CHANGED", caught.exception.code)
                execute.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
