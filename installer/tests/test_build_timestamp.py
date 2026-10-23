import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("timestamp_runner", ROOT / "installer/v1/runner.py")
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class BuildTimestampTests(unittest.TestCase):
    def test_utc_and_offsets_preserve_instant(self):
        cases = {
            "2026-10-22T17:41:14Z": "2026-10-22T17:41:14Z",
            "2026-10-22T20:41:14+03:00": "2026-10-22T17:41:14Z",
            "2026-01-01T00:15:00+05:30": "2025-12-31T18:45:00Z",
            "2026-12-31T23:30:00-02:00": "2027-01-01T01:30:00Z",
        }
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                self.assertEqual(expected, r.build_timestamp_utc(raw))

    def test_invalid_or_ambiguous_metadata_rejected(self):
        for raw in (None, 123, "", "2026-10-22T20:41:14", "2026-10-22", "2026-02-30T00:00:00Z",
                    "2026-10-22T20:41:14.123Z", "2026-10-22T20:41:14+24:00", "2026-10-22T20:41:14+01:60",
                    "2026-10-22T20:41:14Z\n", "2026-10-22 20:41:14Z", "0001-01-01T00:00:00+01:00"):
            with self.subTest(raw=raw), self.assertRaises(r.InstallError) as caught:
                r.build_timestamp_utc(raw)
            self.assertEqual("RELEASE_INVALID", caught.exception.code)

    def test_signing_canonicalizes_before_signing_and_rejects_bad_input_without_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            bundle = root / "bundle"
            shutil.copytree(ROOT / "installer/v1", bundle, ignore=shutil.ignore_patterns("__pycache__"))
            key = root / "test-only.pem"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(key)],
                           check=True, capture_output=True)
            recipe = json.loads((bundle / "release.json").read_bytes())
            recipe["builtAt"] = "2026-10-22T20:41:14+03:00"
            (bundle / "release.json").write_bytes(r.canonical(recipe))
            command = [sys.executable, str(ROOT / "installer/tools/sign_release.py"), "--bundle", str(bundle), "--key", str(key)]
            subprocess.run(command, check=True, capture_output=True)
            verified = r.verify_bundle(*[(bundle / name).read_bytes() for name in
                                        ("runner.py", "trust-root.pem", "release.json", "release.sig", "docker.asc")])
            self.assertEqual("2026-10-22T17:41:14Z", verified["builtAt"])
            recipe["builtAt"] = "not a timestamp"
            (bundle / "release.json").write_bytes(r.canonical(recipe))
            before = {p.name: p.read_bytes() for p in bundle.iterdir() if p.is_file()}
            result = subprocess.run(command, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(before, {p.name: p.read_bytes() for p in bundle.iterdir() if p.is_file()})


if __name__ == "__main__":
    unittest.main()
