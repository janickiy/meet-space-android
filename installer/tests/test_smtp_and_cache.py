"""Verify SMTP secret isolation and resumable image caches without live credentials."""
import contextlib
import copy
import json
import pathlib
import stat
import tempfile
import time
import types
import unittest
import uuid
from unittest.mock import patch
from test_runner import r, ROOT
import test_resource_profiles


class SMTPTests(unittest.TestCase):
    def setUp(self):
        self.smtp = {"host": "smtp.example.org", "port": 587, "from": "notify@example.org",
                     "username": "notify@example.org", "tlsMode": "starttls"}
        self.options = {"domain": "meet.example.org", "publicIpv4": "8.8.8.8", "smtp": self.smtp}
        self.manifest = json.loads((ROOT / "installer/v1/release.json").read_bytes())
        self.password = "secret$${HOME} 'quotes' \\tail\\"

    def rejected(self, code, function, *args):
        with self.assertRaises(r.InstallError) as error:
            function(*args)
        self.assertEqual(code, error.exception.code)

    def test_legacy_options_keep_original_shape_and_smtp_is_optional(self):
        legacy = {key: value for key, value in self.options.items() if key != "smtp"}
        self.assertEqual(dict(legacy, acmeEmail=""), r.validate_options(legacy, self.manifest))
        normalized = r.validate_options(self.options, self.manifest)
        self.assertEqual(self.smtp, normalized["smtp"])
        self.assertNotIn("password", normalized["smtp"])

    def test_rejects_password_in_plan_and_invalid_smtp_fields(self):
        candidates = [None, {}, dict(self.smtp, password="secret"), dict(self.smtp, unexpected=True)]
        for key, values in {
            "host": ["https://smtp.example.org", "host:465", "a\nhost", "fe80::1%eth0", "-host", False],
            "port": [0, 65536, "587", True], "tlsMode": ["none", None],
            "from": ["x@example.org\nBcc:victim@example.org", "a@example.org,b@example.org", ".a@example.org"],
            "username": ["", "name\n", "а" * 257, "\ud800"],
        }.items():
            candidates.extend(dict(self.smtp, **{key: value}) for value in values)
        for candidate in candidates:
            with self.subTest(candidate=candidate):
                self.rejected("INVALID_SMTP", r.validate_smtp, candidate)

    def test_secret_has_utf8_byte_bound_and_rejects_line_injection(self):
        for password in (None, "", "x\r\nSMTP_HOST=bad", "x\x00", "а" * 2049, "\ud800"):
            self.rejected("SMTP_PASSWORD_REQUIRED", r.validate_smtp_password, password)
        self.assertEqual("а" * 2048, r.validate_smtp_password("а" * 2048))

    def test_atomic_initialization_stores_password_only_in_private_env(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, "no_symlink"):
            root = pathlib.Path(directory) / "meetspace"
            value = str(uuid.uuid4())
            plan = {"options": self.options}
            bundle = {"runner.py": b"public source", "release.json": b"public metadata"}
            with patch.object(r, "ROOT", root):
                r.initialize_layout(value, plan, self.manifest, bundle, self.password)
            smtp_file = root / "config/smtp.env"
            self.assertEqual(0o600, smtp_file.stat().st_mode & 0o777)
            self.assertEqual(r.smtp_environment(self.smtp, self.password), smtp_file.read_bytes())
            for file in root.rglob("*"):
                if file.is_file() and file != smtp_file:
                    self.assertNotIn(b"SMTP_PASSWORD", file.read_bytes())
                    self.assertNotIn(self.password.encode(), file.read_bytes())

    def test_private_smtp_config_detects_tampering_permissions_and_missing_secrets(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, "no_symlink"):
            path = pathlib.Path(directory) / "smtp.env"
            raw = r.smtp_environment(self.smtp, self.password)
            path.write_bytes(raw)
            attributes = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_size=len(raw))
            with patch.object(pathlib.Path, "stat", return_value=attributes):
                r.verify_smtp_environment(path, self.smtp)
                self.rejected("SMTP_CONFIG_INVALID", r.verify_smtp_environment, path, dict(self.smtp, host="other.example.org"))
                path.write_bytes(raw + b'SMTP_HOST="other.example.org"\n')
                self.rejected("SMTP_CONFIG_INVALID", r.verify_smtp_environment, path, self.smtp)
            path.write_bytes(raw)
            attributes.st_mode = stat.S_IFREG | 0o644
            with patch.object(pathlib.Path, "stat", return_value=attributes):
                self.rejected("SMTP_CONFIG_INVALID", r.verify_smtp_environment, path, self.smtp)

    def test_apply_requires_secret_before_initializing_smtp_install(self):
        plan = {"options": self.options, "issuedAt": int(time.time()), "expiresAt": int(time.time()) + 900,
                "docker": {"installed": True}, "resourceProfile": "standard"}
        plan["hash"] = r.digest(r.canonical(plan))
        request = {"plan": plan, "confirmedPlanHash": plan["hash"], "jobId": str(uuid.uuid4()), "acceptAcmeTerms": True}
        with patch.object(r, "manifest_for_plan", return_value=self.manifest), \
                patch.object(r, "lock_file", return_value=contextlib.nullcontext()), \
                patch.object(r, "managed_marker", return_value=None), \
                patch.object(r, "create_plan", return_value=plan), \
                patch.object(r, "initialize_layout") as initialize, patch.object(r, "start_unit"), \
                patch.object(r, "job_directory", return_value=pathlib.Path("/unused/job")), \
                patch.object(r, "status", return_value={"state": "running"}):
            self.rejected("SMTP_PASSWORD_REQUIRED", r.apply, request, self.manifest, {})
            initialize.assert_not_called()
            r.apply(dict(request, smtpPassword=self.password), self.manifest, {})
            self.assertEqual(self.password, initialize.call_args.args[-1])
            self.assertNotIn(self.password, json.dumps(initialize.call_args.args[1]))

    def test_compose_exposes_smtp_only_to_api_and_product_worker(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(r, "no_symlink"):
            worker = object.__new__(r.Worker)
            worker.options = self.options
            worker.directory = pathlib.Path(directory) / "job"
            worker.config = pathlib.Path(directory)
            worker.compose_file = worker.config / "compose.json"
            worker.env_file = worker.config / "server.env"
            values = {key: "example" for key in ("APP_ENV", "PUBLIC_FRONTEND_URL", "MINIO_PUBLIC_ENDPOINT",
                      "RABBIT_MQ_USER", "RABBIT_MQ_PASSWORD", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD", "MINIO_BUCKET")}
            images = {name: "sha256:" + "a" * 64 for name in [*r.APP_SERVICES, "proxy", "postgres", "redis", "rabbitmq", "coturn"]}
            worker.write_compose(values, images)
            services = json.loads(worker.compose_file.read_bytes())["services"]
            recipients = {name for name, service in services.items() if str(worker.config / "smtp.env") in service.get("env_file", [])}
            self.assertEqual({"api", "product-worker"}, recipients)
            for name in recipients:
                self.assertEqual("smtp", services[name]["environment"]["EMAIL_PROVIDER_MODE"])
            self.assertNotIn(self.password, worker.compose_file.read_text())


class CacheTests(unittest.TestCase):
    def test_compact_budget_is_reasserted_after_upstream_arg_defaults(self):
        source = "FROM golang:1.26-alpine AS build\nARG GOFLAGS=-p=2\nARG GOMEMLIMIT=2GiB\nRUN go build ./cmd/api\n"
        result = r.pinned_dockerfile(source, {"golang:1.26-alpine": "golang@sha256:" + "a" * 64}, "compact-test")
        final_budget = result.rindex("ENV GOMAXPROCS=2 GOFLAGS=-p=1 GOMEMLIMIT=1536MiB")
        self.assertLess(result.index("ARG GOFLAGS=-p=2"), final_budget)
        self.assertLess(final_budget, result.index("RUN "))

    def test_existing_multiline_go_cache_mounts_are_not_duplicated(self):
        source = ('FROM golang:1.26-alpine AS build\nARG TARGETARCH\n'
                  'RUN --mount=type=cache,id=existing-modules,target=/go/pkg/mod,sharing=locked \\\n'
                  '    --mount=type=cache,id=existing-build,target=/root/.cache/go-build,sharing=locked \\\n'
                  '    go build ./cmd/api\n')
        result = r.pinned_dockerfile(source, {"golang:1.26-alpine": "golang@sha256:" + "a" * 64})
        self.assertEqual(1, result.count("target=/go/pkg/mod"))
        self.assertEqual(1, result.count("target=/root/.cache/go-build"))
        self.assertEqual(1, result.count("ARG TARGETARCH"))
        self.assertIn("id=existing-build", result)

    def test_new_caches_are_scoped_by_toolchain_and_target_architecture(self):
        source = "FROM golang:1.26-alpine AS build\nRUN go mod download\nRUN go build ./cmd/api\n"
        first = r.pinned_dockerfile(source, {"golang:1.26-alpine": "golang@sha256:" + "a" * 64})
        second = r.pinned_dockerfile(source, {"golang:1.26-alpine": "golang@sha256:" + "b" * 64})
        self.assertNotEqual(first.split("id=", 1)[1], second.split("id=", 1)[1])
        self.assertIn("-${TARGETARCH},target=/go/pkg/mod,sharing=locked", first)
        self.assertIn("-${TARGETARCH},target=/root/.cache/go-build,sharing=locked", first)

    def test_resume_skips_completed_infrastructure_pulls_and_disk_build_reserve(self):
        fixture = test_resource_profiles.ImagePhaseResourceTests()
        with fixture.fixture("compact-test") as (worker, _), patch.object(r, "APP_SERVICES", ()):
            images = {name: fixture.details(reference)["Id"] for name, reference in worker.manifest["images"].items()}
            r.write_atomic(worker.images_file, r.canonical(images))
            with patch.object(worker, "image_details", side_effect=fixture.details), \
                    patch.object(r, "run") as execute, patch.object(worker, "check_build_space") as space:
                worker.phase_images()
                worker.phase_images()
            execute.assert_not_called()
            space.assert_not_called()
            timings = r.read_json(worker.directory / "image-timings.json")
            self.assertEqual(set(images), set(timings))
            self.assertTrue(all(value["reuseCount"] == 1 and value["elapsedSeconds"] >= 0 for value in timings.values()))

    def test_resume_rejects_changed_infrastructure_id_without_pulling(self):
        fixture = test_resource_profiles.ImagePhaseResourceTests()
        with fixture.fixture("standard") as (worker, _):
            r.write_atomic(worker.images_file, r.canonical({"postgres": "sha256:" + "f" * 64}))
            with patch.object(worker, "image_details", side_effect=fixture.details), patch.object(r, "run") as execute:
                with self.assertRaises(r.InstallError) as error:
                    worker.phase_images()
            self.assertEqual("IMAGE_CHANGED", error.exception.code)
            execute.assert_not_called()

    def test_cached_image_inspection_checks_platform_and_valid_id(self):
        worker = object.__new__(r.Worker)
        worker.plan = {"platform": {"arch": "amd64"}}
        for details in ({"Id": "sha256:" + "a" * 64, "Architecture": "arm64"},
                        {"Id": "mutable-tag", "Architecture": "amd64"}):
            with self.subTest(details=details), patch.object(r, "run", return_value=r.canonical(details)):
                with self.assertRaises(r.InstallError) as error:
                    worker.image_details("image@sha256:" + "b" * 64)
                self.assertEqual("IMAGE_INVALID", error.exception.code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
