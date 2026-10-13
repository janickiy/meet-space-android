"""Regression checks for mandatory notices and separation of historical evidence."""
import importlib.util
import io
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('stage09_verifier', ROOT / 'testing/stage09/verify_artifacts.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ArtifactVerifierTest(unittest.TestCase):
    def archive(self, prefix='', content=None):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            if content is not None:
                archive.writestr(prefix + 'assets/licenses/jsch-LICENSE.txt', content)
        data.seek(0)
        return zipfile.ZipFile(data)

    def test_exact_publisher_notice_is_required_for_both_apk_and_aab(self):
        notice = (ROOT / 'app/src/main/assets/licenses/jsch-LICENSE.txt').read_bytes()
        for prefix in ('', 'base/'):
            with self.subTest(prefix=prefix), self.archive(prefix, notice) as archive:
                results = verifier.checks.required_license_results(archive, prefix)
                self.assertEqual(1, len(results))
                self.assertTrue(results[0]['matchesPinnedNotice'])

    def test_missing_notice_fails_even_when_source_discovery_is_empty(self):
        with self.archive() as archive:
            results = verifier.checks.required_license_results(archive, '')
        self.assertFalse(results[0]['matchesPinnedNotice'])
        self.assertIsNone(results[0]['sha256'])

    def test_changed_notice_cannot_pass_by_matching_a_changed_source_asset(self):
        with self.archive(content=b'JSch license replaced by a placeholder') as archive:
            results = verifier.checks.required_license_results(archive, '')
        self.assertFalse(results[0]['matchesPinnedNotice'])

    def test_aab_notice_must_be_under_base_assets(self):
        notice = (ROOT / 'app/src/main/assets/licenses/jsch-LICENSE.txt').read_bytes()
        with self.archive(content=notice) as archive:
            self.assertFalse(verifier.checks.required_license_results(archive, 'base/')[0]['matchesPinnedNotice'])

    def test_historical_reports_cannot_be_output_targets(self):
        for value in ('docs/android/evidence/08/artifact-verification.json',
                      'docs/android/evidence/09/../08/replaced.json',
                      'docs/android/evidence/09/security-baseline.json',
                      'docs/android/evidence/09/environment-baseline.json'):
            with self.subTest(path=value), self.assertRaises(ValueError):
                verifier.report_path(value)
        self.assertEqual(ROOT / 'build/reports/stage09/result.json', verifier.report_path('build/reports/stage09/result.json'))

    def test_bundle_manifest_xml_ignores_jvm_stderr_notice(self):
        manifest = '<manifest package="com.meetspace.app" versionName="0.9.0" versionCode="8"><application /></manifest>'
        replies = [
            SimpleNamespace(returncode=0, stdout='', stderr=''),
            SimpleNamespace(returncode=0, stdout=manifest, stderr='Picked up JAVA_TOOL_OPTIONS: -Duser.language=en\n'),
            SimpleNamespace(returncode=0, stdout='PAGE_ALIGNMENT_16K', stderr=''),
            SimpleNamespace(returncode=0, stdout='jar is unsigned.', stderr=''),
        ]
        with TemporaryDirectory(dir=ROOT) as folder:
            bundle = Path(folder) / 'test.aab'
            bundle.write_bytes(b'placeholder; ZIP inspection is a separate check')
            with mock.patch.object(verifier.checks.subprocess, 'run', side_effect=replies), \
                    mock.patch.object(verifier.checks, 'inspect_zip', return_value={'jarSignatureEntriesAbsent': True}):
                result = verifier.checks.inspect_bundle(bundle, {'JAVA_HOME': '/unused'}, Path('/unused/java'), None)
        self.assertEqual('com.meetspace.app', result['manifest']['package'])
        self.assertEqual(8, result['manifest']['versionCode'])

    def test_malformed_manifest_stdout_is_not_repaired_from_stderr_or_logged(self):
        secret_sentinel = 'DO_NOT_REPORT_MANIFEST_OR_TOOL_CONTENT'
        replies = [
            SimpleNamespace(returncode=0, stdout='', stderr=''),
            SimpleNamespace(returncode=0, stdout=secret_sentinel, stderr='<manifest />'),
        ]
        with TemporaryDirectory(dir=ROOT) as folder:
            bundle = Path(folder) / 'test.aab'
            bundle.write_bytes(b'placeholder')
            with mock.patch.object(verifier.checks.subprocess, 'run', side_effect=replies), \
                    self.assertRaises(verifier.checks.BundleManifestError) as caught:
                verifier.checks.inspect_bundle(bundle, {'JAVA_HOME': '/unused'}, Path('/unused/java'), None)
        self.assertIn('bundletool manifest stdout is invalid XML (line 1, column 0)', str(caught.exception))
        self.assertNotIn(secret_sentinel, str(caught.exception))

    def test_failed_manifest_command_reports_only_exit_code(self):
        replies = [
            SimpleNamespace(returncode=0, stdout='', stderr=''),
            SimpleNamespace(returncode=7, stdout='PRIVATE_STDOUT', stderr='PRIVATE_STDERR'),
        ]
        with TemporaryDirectory(dir=ROOT) as folder:
            bundle = Path(folder) / 'test.aab'
            bundle.write_bytes(b'placeholder')
            with mock.patch.object(verifier.checks.subprocess, 'run', side_effect=replies), \
                    self.assertRaises(verifier.checks.BundleManifestError) as caught:
                verifier.checks.inspect_bundle(bundle, {'JAVA_HOME': '/unused'}, Path('/unused/java'), None)
        self.assertEqual('bundletool dump manifest failed (exit code 7).', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
