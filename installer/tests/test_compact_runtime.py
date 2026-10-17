"""Verify the compact profile's host reserve and retained service capabilities."""
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from test_runner import r, ROOT


class CompactRuntimeTests(unittest.TestCase):
    def worker(self, root, profile):
        worker = object.__new__(r.Worker)
        worker.plan = {"resourceProfile": profile}
        worker.directory = root / "jobs" / "test-job"
        worker.directory.mkdir(parents=True)
        worker.config = root / "config"
        worker.compose_file = worker.config / "compose.json"
        worker.env_file = worker.config / "server.env"
        worker.release = root / "release"
        worker.release.mkdir()
        worker.images_file = worker.directory / "images.json"
        worker.options = {"domain": "meet.example.com", "publicIpv4": "8.8.8.8", "acmeEmail": ""}
        worker.manifest = json.loads((ROOT / "installer/v1/release.json").read_bytes())
        worker.images_file.write_bytes(r.canonical({name: "sha256:" + "a" * 64 for name in [*r.APP_SERVICES, *worker.manifest["images"]]}))
        (worker.release / ".env.production.example").write_text("APP_ENV=production\nMINIO_BUCKET=recordings\nTRANSCRIPTION_ENABLED=true\nAI_SUMMARY_ENABLED=true\nRECORDING_MAX_ACTIVE=2\n")
        (worker.release / "dockers/production").mkdir(parents=True)
        (worker.release / "dockers/production/routes.conf").write_text("location / { proxy_pass http://$frontend_upstream; }\n")
        (worker.release / "dockers/turn").mkdir(parents=True)
        (worker.release / "dockers/turn/turnserver.conf").write_text("use-auth-secret\ncert=/run/certs/fullchain.pem\npkey=/run/certs/privkey.pem\n")
        return worker

    def configure(self, root, worker):
        network = {"Labels": {"io.meetspace.installer": worker.directory.name}, "IPAM": {"Config": [{"Subnet": "172.30.0.0/24"}]}}
        with patch.object(r, "ROOT", root), patch.object(r, "no_symlink"), patch.object(r.os, "chown"), patch.object(r, "run", return_value=r.canonical(network)), patch.object(worker, "compose"):
            worker.phase_configuration()
        return r.strict_json(worker.compose_file.read_bytes()), worker.environment()

    def test_compact_budget_reserves_host_memory_and_keeps_every_service(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            worker = self.worker(root, "compact-test")
            document, values = self.configure(root, worker)
            services = document["services"]
            self.assertEqual(set(r.APP_SERVICES) | {"proxy", "postgres", "redis", "rabbitmq", "coturn", "migrate"}, set(services))
            total = sum(int(service["mem_limit"].removesuffix("m")) for name, service in services.items() if name != "migrate")
            self.assertLessEqual(total, 3 * 1024 - 944)
            self.assertTrue(all(service["mem_limit"] == service["memswap_limit"] for service in services.values()))
            self.assertEqual({"proxy", "media-worker", "coturn"}, {name for name, service in services.items() if "ports" in service})
            self.assertIn("--requirepass", services["redis"]["command"][-1])
            self.assertIn("--maxmemory 32mb", services["redis"]["command"][-1])
            self.assertIn("max_connections=40", services["postgres"]["command"])
            self.assertEqual("+S 1:1 +A 2", services["rabbitmq"]["environment"]["RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS"])
            for name in ("api", "media-worker", "worker", "product-worker", "live-worker", "minio", "migrate"):
                self.assertEqual("2", services[name]["environment"]["GOMAXPROCS"])
                self.assertLess(int(services[name]["environment"]["GOMEMLIMIT"].removesuffix("MiB")), int(services[name]["mem_limit"].removesuffix("m")))
            self.assertNotIn("tmpfs", services["product-worker"])
            self.assertEqual("1000:1000", services["product-worker"]["user"])
            self.assertIn(str(root / "product-tmp") + ":/tmp", services["product-worker"]["volumes"])
            self.assertEqual(0o700, (root / "product-tmp").stat().st_mode & 0o777)
            self.assertEqual("true", values["TRANSCRIPTION_ENABLED"])
            self.assertEqual("true", values["AI_SUMMARY_ENABLED"])
            self.assertEqual("10", values["MEDIA_MAX_PEERS"])
            self.assertEqual("1", values["RECORDING_MAX_ACTIVE"])
            self.assertEqual("1", values["RECORDING_FFMPEG_CONCURRENCY"])
            self.assertEqual("2048", values["RECORDING_DISK_RESERVE_MIB"])
            self.assertEqual("1", values["PRODUCT_DELIVERY_CONCURRENCY"])
            self.assertEqual("1", values["AI_CHUNK_CONCURRENCY"])
            self.assertEqual("3", values["DB_MAX_OPEN"])
            self.assertTrue(values["JWT_SECRET"] and values["TURN_SHARED_SECRET"])
            turn = (root / "config/turnserver.conf").read_text()
            self.assertIn("use-auth-secret", turn)
            self.assertIn("cert=/run/certs/fullchain.pem", turn)
            self.assertIn("static-auth-secret=" + values["TURN_SHARED_SECRET"], turn)

    def test_standard_runtime_keeps_existing_budgets_and_concurrency(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            worker = self.worker(root, "standard")
            document, values = self.configure(root, worker)
            self.assertEqual("2g", document["services"]["worker"]["mem_limit"])
            self.assertEqual("512m", document["services"]["postgres"]["mem_limit"])
            self.assertEqual(["/tmp:size=512m,mode=1777"], document["services"]["product-worker"]["tmpfs"])
            self.assertNotIn("user", document["services"]["product-worker"])
            self.assertEqual("2", values["RECORDING_MAX_ACTIVE"])
            self.assertEqual("10", values["DB_MAX_OPEN"])
            self.assertNotIn("GOMAXPROCS", values)
            self.assertFalse((root / "product-tmp").exists())

    def test_compact_configuration_cannot_silently_lose_concurrency_limits_on_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            worker = self.worker(root, "compact-test")
            self.configure(root, worker)
            worker.env_file.write_text(worker.env_file.read_text().replace("DB_MAX_OPEN=3\n", "DB_MAX_OPEN=20\n"))
            with self.assertRaises(r.InstallError) as error:
                self.configure(root, worker)
            self.assertEqual("CONFIG_CHANGED", error.exception.code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
