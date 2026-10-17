import importlib.util
import json
import pathlib
import tempfile
import types
import unittest
from unittest.mock import patch


ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("compose_runner", ROOT / "installer/v1/runner.py")
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class ComposePluginTests(unittest.TestCase):
    def setUp(self):
        self.recipe = json.loads((ROOT / "installer/v1/release.json").read_bytes())
        self.key = (ROOT / "installer/v1/docker.asc").read_bytes()
        self.existing = {"installed": True, "version": "27.5.1", "composeVersion": "2.32.4"}
        self.missing = dict(self.existing, composeVersion=None, installCompose=True)
        self.missing_buildx = dict(self.existing, installBuildx=True)
        self.missing_both = dict(self.missing, installBuildx=True)
        self.absent = {"installed": False, "version": None, "composeVersion": None}
        self.buildx_version = b"github.com/docker/buildx v0.38.0 9872040b6626fb7d87ef7296fd5b832e8cc2ad17\n"

    def rejected(self, code, function):
        with self.assertRaises(r.InstallError) as error:
            function()
        self.assertEqual(code, error.exception.code)

    def test_missing_docker_keeps_legacy_facts_keys(self):
        with patch.object(r.shutil, "which", return_value=None), patch.object(r, "run", side_effect=r.InstallError("COMMAND_FAILED")):
            self.assertEqual(self.absent, r.docker_facts())

    def test_compatible_docker_and_plugins_keep_legacy_facts_keys(self):
        with patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=[b"27.5.1\n", b"2.32.4\n", self.buildx_version]) as execute:
            self.assertEqual(self.existing, r.docker_facts())
        self.assertEqual([["docker", "version", "--format", "{{.Server.Version}}"],
                          ["docker", "compose", "version", "--short"], ["docker", "buildx", "version"]],
                         [call.args[0] for call in execute.call_args_list])

    def test_missing_compose_requests_plugin_for_supported_existing_docker(self):
        with patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=[b"27.5.1\n", r.InstallError("COMPOSE_REQUIRED"), self.buildx_version]):
            self.assertEqual(self.missing, r.docker_facts())

    def test_missing_buildx_requests_only_its_plugin(self):
        with patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=[b"27.5.1\n", b"2.32.4\n", r.InstallError("BUILDX_REQUIRED")]):
            self.assertEqual(self.missing_buildx, r.docker_facts())

    def test_missing_compose_does_not_skip_buildx_detection(self):
        with patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=[b"27.5.1\n", r.InstallError("COMPOSE_REQUIRED"), r.InstallError("BUILDX_REQUIRED")]):
            self.assertEqual(self.missing_both, r.docker_facts())

    def test_unsupported_docker_is_rejected_before_compose_lookup(self):
        for version in (b"23.0.6", b"invalid"):
            with self.subTest(version=version), patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", return_value=version) as execute:
                self.rejected("DOCKER_UNSUPPORTED", r.docker_facts)
                self.assertEqual(1, execute.call_count)

    def test_successful_but_unsupported_compose_is_rejected(self):
        for version in (b"1.29.2", b"invalid", b""):
            with self.subTest(version=version), patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=[b"27.5.1", version]):
                self.rejected("DOCKER_UNSUPPORTED", r.docker_facts)

    def test_successful_but_malformed_buildx_output_is_rejected(self):
        for version in (b"invalid", b"", b"github.com/docker/buildx unknown unknown"):
            with self.subTest(version=version), patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=[b"27.5.1", b"2.32.4", version]):
                self.rejected("DOCKER_UNSUPPORTED", r.docker_facts)

    def test_unavailable_daemon_and_unrelated_plugin_errors_are_not_install_requests(self):
        for outputs, code in (([r.InstallError("DOCKER_UNAVAILABLE")], "DOCKER_UNAVAILABLE"),
                              ([b"27.5.1", r.InstallError("RESPONSE_LIMIT")], "RESPONSE_LIMIT"),
                              ([b"27.5.1", b"2.32.4", r.InstallError("RESPONSE_LIMIT")], "RESPONSE_LIMIT"),
                              ([b"27.5.1", b"2.32.4", r.InstallError("DOCKER_UNAVAILABLE")], "DOCKER_UNAVAILABLE")):
            with self.subTest(outputs=outputs, code=code), patch.object(r.shutil, "which", return_value="/usr/bin/docker"), patch.object(r, "run", side_effect=outputs) as execute:
                self.rejected(code, r.docker_facts)
                self.assertEqual(len(outputs), execute.call_count)

    def worker(self):
        worker = object.__new__(r.Worker)
        worker.plan = {"platform": {"arch": "amd64"}, "dockerPackages": self.recipe["dockerPackages"]["amd64"]}
        worker.manifest = self.recipe
        return worker

    def test_existing_docker_and_plugins_are_untouched(self):
        with patch.object(r, "docker_facts", return_value=self.existing), patch.object(r, "run") as execute, patch.object(r, "write_atomic") as write:
            self.worker().phase_docker()
        execute.assert_not_called()
        write.assert_not_called()

    def phase_fixture(self, directory):
        root = pathlib.Path(directory)
        key = root / "installer/v1/docker.asc"
        key.parent.mkdir(parents=True)
        key.write_bytes(self.key)
        (root / "host/etc/apt").mkdir(parents=True)
        paths = types.SimpleNamespace(Path=lambda path: root / "host" / str(path).lstrip("/"))
        return root, paths

    def test_plugin_only_install_uses_signed_repo_pin_and_never_restarts_docker(self):
        cases = ((self.missing, ["docker-compose-plugin"]),
                 (self.missing_buildx, ["docker-buildx-plugin"]),
                 (self.missing_both, ["docker-compose-plugin", "docker-buildx-plugin"]))
        for facts, packages in cases:
            with self.subTest(packages=packages), tempfile.TemporaryDirectory() as directory:
                self.check_plugin_only_install(directory, facts, packages)

    def check_plugin_only_install(self, directory, facts, packages):
        root, paths = self.phase_fixture(directory)
        with patch.object(r, "ROOT", root), patch.object(r, "pathlib", paths), patch.object(r, "docker_facts", side_effect=[facts, self.existing]), patch.object(r, "write_atomic") as write, patch.object(r, "run") as execute:
            self.worker().phase_docker()
        self.assertEqual(2, execute.call_count)
        update, install = execute.call_args_list
        self.assertEqual(["apt-get", "-o", "DPkg::Lock::Timeout=120", "update", "-qq"], update.args[0])
        self.assertEqual(["apt-get", "-o", "DPkg::Lock::Timeout=120", "install", "-y", "--no-install-recommends", "--no-upgrade",
                          *[package + "=" + self.recipe["dockerPackages"]["amd64"][package] for package in packages]], install.args[0])
        self.assertEqual(dict(r.SAFE_ENV, NEEDRESTART_MODE="l"), install.kwargs["env"])
        self.assertNotIn("NEEDRESTART_MODE", r.SAFE_ENV)
        self.assertNotIn("env", update.kwargs)
        writes = {str(call.args[0]): call.args[1:] for call in write.call_args_list}
        key_target = root / "host/etc/apt/keyrings/meetspace-docker.asc"
        self.assertEqual((self.key, 0o644), writes[str(key_target)])
        source, mode = writes[str(root / "host/etc/apt/sources.list.d/meetspace-docker.sources")]
        self.assertEqual(0o644, mode)
        self.assertIn(b"URIs: https://download.docker.com/linux/ubuntu\nSuites: noble\n", source)
        self.assertIn(("Architectures: amd64\nSigned-By: " + str(key_target) + "\n").encode(), source)

    def test_full_docker_install_retains_all_packages_and_service_enable(self):
        with tempfile.TemporaryDirectory() as directory:
            root, paths = self.phase_fixture(directory)
            with patch.object(r, "ROOT", root), patch.object(r, "pathlib", paths), patch.object(r, "docker_facts", side_effect=[self.absent, self.existing]), patch.object(r, "write_atomic"), patch.object(r, "run") as execute:
                self.worker().phase_docker()
            update, install, service = execute.call_args_list
            self.assertEqual("update", update.args[0][3])
            self.assertEqual(["apt-get", "-o", "DPkg::Lock::Timeout=120", "install", "-y", "--no-install-recommends",
                              *[name + "=" + version for name, version in sorted(self.recipe["dockerPackages"]["amd64"].items())]], install.args[0])
            self.assertNotIn("env", install.kwargs)
            self.assertEqual(["systemctl", "enable", "--now", "docker"], service.args[0])

    def test_plugin_install_requires_verified_key_before_apt(self):
        with tempfile.TemporaryDirectory() as directory:
            root, paths = self.phase_fixture(directory)
            (root / "installer/v1/docker.asc").write_bytes(b"unverified")
            with patch.object(r, "ROOT", root), patch.object(r, "pathlib", paths), patch.object(r, "docker_facts", return_value=self.missing), patch.object(r, "write_atomic") as write, patch.object(r, "run") as execute:
                self.rejected("RELEASE_CHECKSUM", self.worker().phase_docker)
            execute.assert_not_called()
            write.assert_not_called()

    def test_plugin_install_preserves_existing_repo_conflict_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root, paths = self.phase_fixture(directory)
            source = root / "host/etc/apt/sources.list.d/meetspace-docker.sources"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"untrusted source")
            with patch.object(r, "ROOT", root), patch.object(r, "pathlib", paths), patch.object(r, "docker_facts", return_value=self.missing), patch.object(r, "write_atomic"), patch.object(r, "run") as execute:
                self.rejected("APT_SOURCE_CONFLICT", self.worker().phase_docker)
            execute.assert_not_called()

    def test_failed_plugin_postcheck_does_not_complete_docker_phase(self):
        for facts, code in ((self.missing, "COMPOSE_REQUIRED"), (self.missing_buildx, "BUILDX_REQUIRED"),
                            (self.missing_both, "COMPOSE_REQUIRED"), (self.absent, "DOCKER_UNAVAILABLE")):
            with self.subTest(facts=facts), tempfile.TemporaryDirectory() as directory:
                root, paths = self.phase_fixture(directory)
                with patch.object(r, "ROOT", root), patch.object(r, "pathlib", paths), patch.object(r, "docker_facts", side_effect=[self.missing_both, facts]), patch.object(r, "write_atomic"), patch.object(r, "run"):
                    self.rejected(code, self.worker().phase_docker)


if __name__ == "__main__":
    unittest.main(verbosity=2)
