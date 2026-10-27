"""Backup verification must not allocate a database dump in the installer heap."""
import contextlib
import hashlib
import pathlib
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
import uuid
from unittest.mock import patch

from test_runner import r


class BackupStreamingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        (self.root / 'backups').mkdir()
        self.worker = object.__new__(r.Worker)
        self.worker.directory = self.root / 'jobs' / str(uuid.uuid4())
        self.worker.release = self.root / 'release'
        self.worker.env_file = self.root / 'private' / 'server.env'
        self.worker.compose_file = self.root / 'private' / 'compose.json'
        self.target = self.root / 'backups' / (self.worker.directory.name + '-before-migrations.dump')
        self.partial = self.target.with_suffix('.partial')
        self.receipt = self.target.with_suffix('.json')
        self.dump_size = 4096
        self.dump_result = self.restore_result = 0
        self.executed = []
        self.stdin_stream = None
        self.restore_size = 0
        self.restore_digest = None
        original_read_bytes = pathlib.Path.read_bytes

        def read_metadata_only(path):
            if path.suffix in ('.dump', '.partial'):
                raise AssertionError('A complete backup was read into Python memory')
            return original_read_bytes(path)

        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(r, 'ROOT', self.root))
        self.stack.enter_context(patch.object(r, 'no_symlink'))
        self.stack.enter_context(patch.object(pathlib.Path, 'read_bytes', read_metadata_only))

    def subprocess(self, args, **kwargs):
        self.executed.append(args)
        if args[0] == 'docker' and 'pg_dump' in args[-1]:
            # A sparse dump represents an arbitrarily large database without
            # allocating the fake dump's content in the test process either.
            kwargs['stdout'].truncate(self.dump_size)
            return subprocess.CompletedProcess(args, self.dump_result)
        if args[0] == 'docker' and 'pg_restore' in args:
            self.assertIsNone(kwargs.get('input'))
            self.stdin_stream = kwargs.get('stdin')
            self.assertIsNotNone(self.stdin_stream)
            self.assertFalse(self.stdin_stream.closed)
            checksum = hashlib.sha256()
            for block in iter(lambda: self.stdin_stream.read(64 * 1024), b''):
                checksum.update(block)
                self.restore_size += len(block)
            self.restore_digest = checksum.hexdigest()
            return subprocess.CompletedProcess(args, self.restore_result, stdout=b'')
        self.fail('Unexpected process in backup fixture')

    def backup(self):
        with patch.object(r.subprocess, 'run', side_effect=self.subprocess):
            self.worker.phase_backup()

    def assert_error(self, expected):
        with self.assertRaises(r.InstallError) as caught:
            self.backup()
        self.assertEqual(expected, caught.exception.code)

    def test_new_backup_streams_validation_and_publishes_matching_receipt(self):
        self.backup()
        self.assertEqual(self.dump_size, self.target.stat().st_size)
        self.assertEqual(self.dump_size, self.restore_size)
        self.assertEqual(r.file_digest(self.target), self.restore_digest)
        receipt = r.read_json(self.receipt)
        self.assertEqual(self.restore_digest, receipt['sha256'])
        self.assertTrue(receipt['beforeMigrations'])
        self.assertFalse(receipt['restoreDrillPerformed'])
        self.assertEqual(self.worker.directory.name, receipt['jobId'])
        self.assertFalse(self.partial.exists())
        self.assertTrue(self.stdin_stream.closed)
        self.assertEqual(0o600, self.target.stat().st_mode & 0o777)

    def test_valid_backup_reuse_checks_hash_without_a_process_or_complete_file_read(self):
        self.backup()
        self.executed.clear()
        self.backup()
        self.assertEqual([], self.executed)

    def test_corrupt_existing_backup_is_preserved_and_rejected(self):
        self.backup()
        with self.target.open('ab') as stream:
            stream.write(b'corrupt')
        self.executed.clear()
        self.assert_error('BACKUP_INVALID')
        self.assertEqual([], self.executed)
        self.assertTrue(self.target.exists())
        self.assertTrue(self.receipt.exists())

    def test_dump_failure_removes_partial_and_publishes_nothing(self):
        self.dump_result = 1
        self.assert_error('BACKUP_FAILED')
        self.assertFalse(self.partial.exists())
        self.assertFalse(self.target.exists())
        self.assertFalse(self.receipt.exists())

    def test_restore_failure_closes_stdin_and_removes_unverified_partial(self):
        self.restore_result = 1
        self.assert_error('BACKUP_INVALID')
        self.assertTrue(self.stdin_stream.closed)
        self.assertFalse(self.partial.exists())
        self.assertFalse(self.target.exists())
        self.assertFalse(self.receipt.exists())

    def test_large_backup_heap_use_is_bounded_independently_of_dump_size(self):
        peaks = []
        for size in (8 * 1024 ** 2, 64 * 1024 ** 2):
            self.dump_size = size
            self.restore_size = 0
            self.target.unlink(missing_ok=True)
            self.receipt.unlink(missing_ok=True)
            tracemalloc.start()
            try:
                self.backup()
                _, peak = tracemalloc.get_traced_memory()
            finally:
                tracemalloc.stop()
            peaks.append(peak)
            self.assertEqual(size, self.restore_size)
            self.assertLess(peak, 4 * 1024 ** 2)
        self.assertLess(abs(peaks[1] - peaks[0]), 1024 ** 2)

    def test_run_passes_file_stdin_to_an_actual_local_subprocess(self):
        path = self.root / 'stdin-fixture'
        path.write_bytes(b'synthetic stdin')
        with path.open('rb') as stream:
            result = r.run([sys.executable, '-c', 'import sys; print(len(sys.stdin.buffer.read()))'], stdin=stream, capture=True)
        self.assertEqual(b'15\n', result)

    def test_run_rejects_ambiguous_bytes_and_file_stdin(self):
        with self.assertRaises(r.InstallError) as caught:
            r.run(['never-executed'], data=b'input', stdin=object())
        self.assertEqual('INVALID_REQUEST', caught.exception.code)


if __name__ == '__main__':
    unittest.main()
