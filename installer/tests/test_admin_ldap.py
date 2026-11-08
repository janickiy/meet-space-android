"""Administrator deployment boundaries and LDAP key continuity, without live secrets."""
import contextlib
import json
import pathlib
import stat
import tempfile
import time
import types
import unittest
import uuid
from unittest.mock import Mock, patch
from test_runner import ROOT, r
import test_compact_runtime


class AdminLDAPTests(unittest.TestCase):
    def setUp(self):
        self.admin = {'username': 'ops+android@example.org', 'httpsPort': 8081}
        self.options = {'domain': 'meet.example.org', 'publicIpv4': '8.8.8.8', 'admin': self.admin}
        self.manifest = json.loads((ROOT / 'installer/v1/release.json').read_bytes())
        self.password = 'Test secret \U0001f512 42'

    def rejected(self, code, call, *args):
        with self.assertRaises(r.InstallError) as error:
            call(*args)
        self.assertEqual(code, error.exception.code)

    def test_public_options_cannot_include_password_or_reserved_port(self):
        self.assertEqual(self.admin, r.validate_options(self.options, self.manifest)['admin'])
        for value in (None, {}, dict(self.admin, password='secret'), dict(self.admin, username='UPPER'),
                      *[dict(self.admin, httpsPort=port) for port in (False, 0, 443, 8082, 3478, 5349, 50010, 49160, 49259, 65536)]):
            self.rejected('INVALID_ADMIN', r.validate_admin, value)
        self.assertNotIn('admin', r.validate_options({k: v for k, v in self.options.items() if k != 'admin'}, self.manifest))

    def test_password_matches_backend_unicode_contract(self):
        for value in ('12345678', '\U0001f512' * 128, 'a' * 128, self.password):
            self.assertEqual(value, r.validate_admin_password(value))
        for value in (None, False, '', 'short', 'a' * 129, '\U0001f512' * 129, 'long\rpass', 'long\npass', 'long\0pass', '\ud800' * 8):
            self.rejected('ADMIN_PASSWORD_REQUIRED', r.validate_admin_password, value)

    def test_admin_port_is_checked_before_apply_and_in_public_plan(self):
        with patch.object(r.shutil, 'which', return_value='/usr/bin/ss'), patch.object(r, 'run', return_value=b'LISTEN 0 128 0.0.0.0:8081 0.0.0.0:*\n'):
            self.rejected('PORT_OCCUPIED', r.check_ports, self.options)
            r.check_ports({})
        facts = dict(os='ubuntu', osVersion='24.04', arch='arm64', cpus=2, memoryKiB=4 * 1024**2)
        with patch.object(r, 'platform_facts', return_value=facts), patch.object(r, 'managed_marker', return_value=None), patch.object(r, 'disk_free', return_value=20 * 1024**3), patch.object(r, 'docker_facts', return_value={'installed': False}), patch.object(r, 'check_ports') as ports, patch.object(r, 'dns_check', return_value=['8.8.8.8']), patch.object(r.pathlib.Path, 'exists', return_value=False):
            plan = r.create_plan(self.options, self.manifest)
        self.assertIn(8081, plan['ports']['tcp'])
        self.assertIn('bootstrap-admin', plan['actions'])
        self.assertEqual(self.admin, ports.call_args.args[0]['admin'])
        self.assertNotIn('password', json.dumps(plan).lower())

    def test_apply_requires_password_before_initialization(self):
        plan = dict(options=self.options, issuedAt=int(time.time()), expiresAt=int(time.time()) + 900,
                    docker={'installed': True}, resourceProfile='compact-test')
        plan['hash'] = r.digest(r.canonical(plan))
        request = dict(plan=plan, confirmedPlanHash=plan['hash'], jobId=str(uuid.uuid4()), acceptAcmeTerms=True)
        with patch.object(r, 'manifest_for_plan', return_value=self.manifest), patch.object(r, 'lock_file', return_value=contextlib.nullcontext()), patch.object(r, 'managed_marker', return_value=None), patch.object(r, 'create_plan', return_value=plan), patch.object(r, 'initialize_layout') as initialize, patch.object(r, 'start_unit'), patch.object(r, 'job_directory'), patch.object(r, 'status', return_value={}):
            self.rejected('ADMIN_PASSWORD_REQUIRED', r.apply, request, self.manifest, {})
            initialize.assert_not_called()
            r.apply(dict(request, adminPassword=self.password), self.manifest, {})
            self.assertEqual(self.password, initialize.call_args.args[5])

    def test_atomic_initialization_has_only_one_private_bootstrap_secret(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'no_symlink'):
            root = pathlib.Path(folder) / 'meetspace'
            with patch.object(r, 'ROOT', root):
                r.initialize_layout(str(uuid.uuid4()), {'options': self.options}, self.manifest, {'runner.py': b'public code'}, admin_password=self.password)
            secret = root / 'config/admin-bootstrap.json'
            self.assertEqual(0o600, secret.stat().st_mode & 0o777)
            self.assertEqual(0o700, secret.parent.stat().st_mode & 0o777)
            self.assertEqual(self.password, json.loads(secret.read_bytes())['password'])
            for path in root.rglob('*'):
                if path.is_file() and path != secret:
                    self.assertNotIn(b'password', path.read_bytes())

    def worker(self, root):
        w = object.__new__(r.Worker)
        w.options = self.options
        w.config = root
        return w

    def test_bootstrap_uses_stdin_then_removes_secret_and_resume_never_resets(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'no_symlink'):
            root = pathlib.Path(folder); worker = self.worker(root)
            secret = root / 'admin-bootstrap.json'
            raw = r.canonical(dict(username=self.admin['username'], password=self.password))
            secret.write_bytes(raw)
            attrs = types.SimpleNamespace(st_uid=0, st_mode=stat.S_IFREG | 0o600, st_size=len(raw))
            state = {'exists': True, 'username': self.admin['username']}
            with patch.object(pathlib.Path, 'stat', return_value=attrs), patch.object(worker, 'compose', side_effect=[b'{"exists":false}', r.canonical(state), r.canonical(state)]) as compose:
                worker.bootstrap_administrator()
            self.assertFalse(secret.exists())
            self.assertEqual(['status', 'bootstrap', 'status'], [c.args[-1] for c in compose.call_args_list])
            self.assertEqual(raw, compose.call_args_list[1].kwargs['data'])
            self.assertNotIn(self.password, repr([c.args for c in compose.call_args_list]))
            with patch.object(worker, 'compose', return_value=r.canonical(state)) as resume:
                worker.bootstrap_administrator()
            self.assertEqual('status', resume.call_args.args[-1])
            self.assertEqual(1, resume.call_count)

    def test_lost_bootstrap_response_preserves_recovery_and_existing_account_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'no_symlink'):
            root = pathlib.Path(folder); worker = self.worker(root)
            secret = root / 'admin-bootstrap.json'
            raw = r.canonical(dict(username=self.admin['username'], password=self.password)); secret.write_bytes(raw)
            attrs = types.SimpleNamespace(st_uid=0, st_mode=stat.S_IFREG | 0o600, st_size=len(raw))
            with patch.object(pathlib.Path, 'stat', return_value=attrs), patch.object(worker, 'compose', side_effect=[b'{"exists":false}', r.InstallError('ADMIN_BOOTSTRAP_FAILED')]):
                self.rejected('ADMIN_BOOTSTRAP_FAILED', worker.bootstrap_administrator)
            self.assertEqual(raw, secret.read_bytes())
            with patch.object(worker, 'compose', return_value=r.canonical({'exists': True, 'username': self.admin['username']})) as reconcile:
                worker.bootstrap_administrator()
            self.assertFalse(secret.exists()); self.assertEqual(1, reconcile.call_count)
            with patch.object(worker, 'compose', return_value=b'{"exists":true,"username":"unrelated"}') as existing:
                self.rejected('ADMIN_ACCOUNT_MISMATCH', worker.bootstrap_administrator)
            self.assertEqual(1, existing.call_count)

    def test_unsafe_secret_permissions_and_owner_never_reach_bootstrap(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(r, 'no_symlink'):
            root = pathlib.Path(folder); worker = self.worker(root)
            secret = root / 'admin-bootstrap.json'; secret.write_text('{}')
            for uid, mode in ((0, 0o644), (501, 0o600)):
                attrs = types.SimpleNamespace(st_uid=uid, st_mode=stat.S_IFREG | mode, st_size=2)
                with patch.object(pathlib.Path, 'stat', return_value=attrs), patch.object(worker, 'compose', return_value=b'{"exists":false}') as command:
                    self.rejected('ADMIN_CONFIG_INVALID', worker.bootstrap_administrator)
                self.assertEqual(1, command.call_count)

    def test_generated_compose_and_tls_isolate_admin_and_keep_ldap_key(self):
        helper = test_compact_runtime.CompactRuntimeTests()
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder); worker = helper.worker(root, 'compact-test'); worker.options = self.options
            (worker.release / 'dockers/production/admin.conf.template').write_text('server { listen 8081 ssl; ssl_certificate @@ADMIN_TLS_CERT@@; ssl_certificate_key @@ADMIN_TLS_KEY@@; set $admin_upstream api:8082; }')
            config, values = helper.configure(root, worker)
            self.assertEqual('true', values['ADMIN_ENABLED'])
            self.assertEqual('https://meet.example.org:8081', values['ADMIN_PUBLIC_URL'])
            self.assertEqual('8082', values['ADMIN_HTTP_PORT'])
            self.assertEqual('8081:8081', config['services']['proxy']['ports'][-1])
            self.assertEqual(['nginx', '-g', 'daemon off;'], config['services']['proxy']['entrypoint'])
            self.assertNotIn('ports', config['services']['api'])
            self.assertNotIn('live-worker', config['services']); self.assertIn('record-worker', config['services'])
            self.assertEqual(config['services']['api']['env_file'], config['services']['product-worker']['env_file'])
            key = values['PROVIDER_TOKEN_ENCRYPTION_KEY']
            self.assertEqual(key, helper.configure(root, worker)[1]['PROVIDER_TOKEN_ENCRYPTION_KEY'])
            admin = worker.config / 'proxy/meetspace-admin.conf'
            self.assertFalse(admin.exists())
            with patch.object(r, 'no_symlink'):
                worker.write_nginx(tls=True)
                self.assertIn('/run/certs/fullchain.pem', admin.read_text()); self.assertNotIn('@@', admin.read_text())
                worker.write_nginx(tls=False)
            self.assertFalse(admin.exists())

    def test_readiness_checks_expected_statuses_with_hostname_sni_over_loopback(self):
        worker = self.worker(pathlib.Path('/unused'))
        context = Mock(); tls = Mock(); context.wrap_socket.return_value = tls
        responses = [Mock(status=n) for n in (200, 401, 404)]
        connection = Mock(); connection.getresponse.side_effect = responses
        with patch.object(r.ssl, 'create_default_context', return_value=context), patch.object(r.socket, 'create_connection') as tcp, patch.object(r.http.client, 'HTTPSConnection', return_value=connection):
            worker.verify_administrator_https()
        self.assertEqual([('127.0.0.1', 8081), ('127.0.0.1', 8081), ('127.0.0.1', 443)], [c.args[0] for c in tcp.call_args_list])
        self.assertTrue(all(c.kwargs['server_hostname'] == 'meet.example.org' for c in context.wrap_socket.call_args_list))
        self.assertEqual(3, connection.close.call_count)
        connection.getresponse.side_effect = None; connection.getresponse.return_value = Mock(status=200)
        with patch.object(r.ssl, 'create_default_context', return_value=context), patch.object(r.socket, 'create_connection'), patch.object(r.http.client, 'HTTPSConnection', return_value=connection):
            self.rejected('ADMIN_HTTPS_READINESS', worker.verify_administrator_https)

    def test_admin_password_rejected_for_non_apply_actions_before_loading_bundle(self):
        with patch.object(r.os, 'geteuid', return_value=0), patch.object(r.os, 'umask'), patch.object(r, 'load_bundle') as load:
            for action in ('plan', 'status', 'resume', 'cancel'):
                self.rejected('INVALID_REQUEST', r.main, action, {'adminPassword': self.password})
            load.assert_not_called()


if __name__ == '__main__':
    unittest.main()
