"""Ready-image transport retries retain one origin, one lock and integrity gates."""
import copy
import http.client
import io
import json
import pathlib
import ssl
import tempfile
import unittest
import urllib.error
import urllib.parse
import urllib.request
from unittest.mock import Mock, patch

from test_runner import ROOT, r


class Response(io.BytesIO):
    def __init__(self, data, headers=None):
        super().__init__(data)
        self.headers = headers or {}


class InterruptedResponse(Response):
    """Deliver a prefix, then fail while the caller is reading the same body."""
    def __init__(self, prefix, error):
        super().__init__(prefix)
        self.error = error
        self.reads = 0

    def read(self, size=-1):
        self.reads += 1
        if self.reads > 1:
            raise self.error
        return super().read(size)


class PrebuiltTransportTests(unittest.TestCase):
    def setUp(self):
        self.url = ('https://github.com/janickiy/meetspace_docker_built/'
                    'releases/download/v0.6.1-prebuilt.test/prebuilt.json')
        self.payload = b'complete verified archive'
        self.lock = dict(url=self.url.replace('prebuilt.json', 'images.tar.gz'),
                         sizeBytes=len(self.payload), sha256=r.digest(self.payload))

    def rejected(self, code, function, *args, **kwargs):
        with self.assertRaises(r.InstallError) as caught:
            function(*args, **kwargs)
        self.assertEqual(code, caught.exception.code)

    def http_error(self, status):
        return urllib.error.HTTPError(self.url, status, 'test response', {}, None)

    def test_transient_http_statuses_retry_the_same_json_operation(self):
        for status in (408, 500, 502, 503, 504):
            with self.subTest(status=status), patch.object(r, 'asset_response', side_effect=[
                    self.http_error(status), Response(b'{"ready":true}')]) as response:
                value, checksum = r.asset_json(self.url)
            self.assertEqual({'ready': True}, value)
            self.assertEqual(r.digest(b'{"ready":true}'), checksum)
            self.assertEqual([(self.url, 0), (self.url, 1)], [call.args for call in response.call_args_list])

    def test_timeout_and_incomplete_json_read_restart_with_fresh_bytes(self):
        for failure in (TimeoutError('read timed out'), http.client.IncompleteRead(b'{"stale":', 10)):
            with self.subTest(error=type(failure).__name__), patch.object(r, 'asset_response', side_effect=[
                    failure, Response(b'{"fresh":1}')]) as response:
                value, checksum = r.asset_json(self.url)
            self.assertEqual({'fresh': 1}, value)
            self.assertEqual(r.digest(b'{"fresh":1}'), checksum)
            self.assertEqual(2, response.call_count)

    def test_incomplete_json_body_read_is_retried_after_the_response_was_opened(self):
        bad = Mock()
        bad.__enter__ = Mock(return_value=bad)
        bad.__exit__ = Mock(return_value=False)
        bad.read.side_effect = http.client.IncompleteRead(b'{"stale":', 10)
        with patch.object(r, 'asset_response', side_effect=[bad, Response(b'{"fresh":2}')]) as response:
            self.assertEqual({'fresh': 2}, r.asset_json(self.url)[0])
        self.assertEqual(2, response.call_count)
        bad.__exit__.assert_called_once()

    def test_content_length_detects_short_json_before_schema_validation(self):
        good = b'{"ready":true}'
        with patch.object(r, 'asset_response', side_effect=[
                Response(b'{"ready":', {'Content-Length': str(len(good))}), Response(good)]) as response:
            self.assertEqual({'ready': True}, r.asset_json(self.url)[0])
        self.assertEqual(2, response.call_count)

    def test_invalid_json_and_duplicate_members_are_not_retried(self):
        for data in (b'{"a":1,"a":2}', b'{"ready":', b'null', b'42'):
            with self.subTest(data=data), patch.object(r, 'asset_response', return_value=Response(data)) as response:
                self.rejected('PREBUILT_INVALID', r.asset_json, self.url)
            response.assert_called_once_with(self.url, 0)

    def test_missing_and_rate_limited_assets_are_not_retried(self):
        for status in (403, 404, 429):
            error = self.http_error(status)
            with self.subTest(status=status), patch.object(r, 'asset_response', side_effect=error) as response:
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    r.asset_json(self.url)
            self.assertIs(error, caught.exception)
            response.assert_called_once_with(self.url, 0)

    def test_json_retries_are_bounded_at_three_attempts(self):
        with patch.object(r, 'asset_response', side_effect=self.http_error(503)) as response:
            with self.assertRaises(urllib.error.HTTPError):
                r.asset_json(self.url)
        self.assertEqual([(self.url, 0), (self.url, 1), (self.url, 2)],
                         [call.args for call in response.call_args_list])

    def test_exhausted_release_lookup_never_switches_to_source_build(self):
        recipe = json.loads((ROOT / 'installer/v1/release.json').read_bytes())
        with patch.object(r, 'asset_response', side_effect=self.http_error(503)) as response, \
                patch.object(r, 'resolve_source') as source, patch.object(r, 'run') as execute:
            self.rejected('PREBUILT_SERVICE_UNAVAILABLE', r.resolve_prebuilt, recipe, 'amd64')
        self.assertEqual(3, response.call_count)
        self.assertEqual(1, len({call.args[0] for call in response.call_args_list}))
        source.assert_not_called()
        execute.assert_not_called()

    def test_certificate_verification_failures_are_not_retried(self):
        for error in (ssl.SSLCertVerificationError('untrusted server'),
                      urllib.error.URLError(ssl.SSLCertVerificationError('untrusted server'))):
            with self.subTest(error=type(error).__name__), patch.object(r, 'asset_response', side_effect=error) as response:
                with self.assertRaises(type(error)):
                    r.asset_json(self.url)
            response.assert_called_once_with(self.url, 0)

    def test_retry_cache_query_preserves_canonical_origin_and_existing_query(self):
        canonical = 'https://api.github.com/repos/janickiy/meetspace_docker_built/releases?per_page=100&page=1'
        opener = Mock()
        with patch.object(r.urllib.request, 'build_opener', return_value=opener), \
                patch.object(r.secrets, 'token_hex', return_value='a' * 24):
            r.asset_response(canonical, 1)
        request = opener.open.call_args.args[0]
        parsed, original = urllib.parse.urlsplit(request.full_url), urllib.parse.urlsplit(canonical)
        self.assertEqual(original._replace(query=''), parsed._replace(query=''))
        self.assertEqual({'per_page': ['100'], 'page': ['1'], '_meetspace_retry': ['a' * 24]},
                         urllib.parse.parse_qs(parsed.query))
        self.assertNotIn('_meetspace_retry', canonical)
        self.assertIsNone(parsed.username)
        self.assertIsNone(parsed.password)
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertEqual('no-cache', headers['cache-control'])
        self.assertEqual('no-cache', headers['pragma'])
        self.assertNotIn('authorization', headers)
        self.assertEqual(30, opener.open.call_args.kwargs['timeout'])

    def test_forbidden_initial_url_never_reaches_the_opener_even_on_retry(self):
        for url in ('http://github.com/a', 'https://github.com.evil.test/a',
                    'https://user:password@github.com/a', 'https://github.com:8443/a'):
            with self.subTest(url=url), patch.object(r.urllib.request, 'build_opener') as opener:
                self.rejected('PREBUILT_INVALID', r.asset_response, url, 2)
            opener.assert_not_called()

    def test_forbidden_redirect_aborts_json_without_retry(self):
        opener = Mock()
        def redirect(request, timeout):
            return r.AssetRedirect().redirect_request(
                request, None, 302, 'redirect', {}, 'https://attacker.invalid/images')
        opener.open.side_effect = redirect
        with patch.object(r.urllib.request, 'build_opener', return_value=opener):
            self.rejected('PREBUILT_INVALID', r.asset_json, self.url)
        self.assertEqual(1, opener.open.call_count)

    def test_archive_read_interruption_restarts_file_and_hash_without_changing_lock(self):
        frozen = copy.deepcopy(self.lock)
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'), \
                patch.object(r, 'disk_free', return_value=10 * 1024 ** 3):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            first = InterruptedResponse(b'stale prefix', ConnectionResetError('connection reset'))
            with patch.object(r, 'asset_response', side_effect=[first, Response(self.payload)]) as response:
                r.download_prebuilt(self.lock, path)
            self.assertEqual(self.payload, path.read_bytes())
            self.assertFalse(path.with_suffix('.partial').exists())
        self.assertEqual([(self.lock['url'], 0), (self.lock['url'], 1)],
                         [call.args for call in response.call_args_list])
        self.assertEqual(frozen, self.lock)
        self.assertTrue(first.closed)

    def test_archive_short_eof_retries_and_never_appends_previous_bytes(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'), \
                patch.object(r, 'disk_free', return_value=10 * 1024 ** 3):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            with patch.object(r, 'asset_response', side_effect=[Response(self.payload[:5]), Response(self.payload)]) as response:
                r.download_prebuilt(self.lock, path)
            self.assertEqual(self.payload, path.read_bytes())
            self.assertFalse(path.with_suffix('.partial').exists())
        self.assertEqual(2, response.call_count)

    def test_full_size_archive_with_wrong_hash_is_not_retried(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'), \
                patch.object(r, 'disk_free', return_value=10 * 1024 ** 3):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            with patch.object(r, 'asset_response', return_value=Response(b'x' * len(self.payload))) as response:
                self.rejected('RELEASE_CHECKSUM', r.download_prebuilt, self.lock, path)
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
        response.assert_called_once_with(self.lock['url'], 0)

    def test_cancelled_before_attempt_removes_partial_without_opening_network(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            path.with_suffix('.partial').write_bytes(b'previous incomplete transfer')
            with patch.object(r, 'asset_response') as response:
                self.rejected('CANCELLED', r.download_prebuilt, self.lock, path, lambda: r.fail('CANCELLED'))
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
        response.assert_not_called()

    def test_archive_failure_after_three_attempts_cleans_partial(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'), \
                patch.object(r, 'disk_free', return_value=10 * 1024 ** 3):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            with patch.object(r, 'asset_response', side_effect=lambda *args: Response(b'short')) as response:
                self.rejected('PREBUILT_DOWNLOAD_FAILED', r.download_prebuilt, self.lock, path)
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
        self.assertEqual(3, response.call_count)

    def test_cancellation_between_archive_attempts_prevents_another_network_request(self):
        checks = 0
        def check_cancel():
            nonlocal checks
            checks += 1
            if checks > 1:
                r.fail('CANCELLED')
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            with patch.object(r, 'asset_response', side_effect=TimeoutError('timed out')) as response:
                self.rejected('CANCELLED', r.download_prebuilt, self.lock, path, check_cancel)
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
        response.assert_called_once_with(self.lock['url'], 0)

    def test_archive_certificate_failure_is_terminal_and_cleans_partial(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            path.with_suffix('.partial').write_bytes(b'previous transfer')
            error = urllib.error.URLError(ssl.SSLCertVerificationError('untrusted server'))
            with patch.object(r, 'asset_response', side_effect=error) as response:
                self.rejected('PREBUILT_DOWNLOAD_FAILED', r.download_prebuilt, self.lock, path)
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
        response.assert_called_once_with(self.lock['url'], 0)

    def test_archive_retries_share_one_deadline_instead_of_resetting_it(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(r, 'no_symlink'), \
                patch.object(r.time, 'monotonic', side_effect=[10, 10, 3611]):
            path = pathlib.Path(temporary) / 'archive.tar.gz'
            with patch.object(r, 'asset_response', side_effect=TimeoutError('timed out')) as response:
                self.rejected('DOWNLOAD_LIMIT', r.download_prebuilt, self.lock, path)
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
        response.assert_called_once_with(self.lock['url'], 0)


if __name__ == '__main__':
    unittest.main()
