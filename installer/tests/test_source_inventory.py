"""The numbered-table baseline is installable without erasing historical jobs."""
import hashlib
import json
import re
import unittest

from test_runner import ROOT, r


TABLES = '''
administrator_audit_log administrator_login_limits administrator_sessions administrators
auth_legacy_tokens auth_session_tokens auth_sessions background_jobs calendar_connections
calendar_event_mappings calendar_oauth_states chat_attachments chat_messages
chat_notification_jobs chat_read_states conference_chat_pins conference_chat_preferences
conference_invitations conference_moderation_audit conference_participants conferences
conversation_attachments conversation_avatar_objects conversation_members
conversation_message_pins conversation_messages conversations direct_call_slots direct_calls
email_verifications error_event_batches error_events folder_conferences folder_conversations
folders integration_deliveries ldap_directory_state ldap_identities ldap_sync_runs
notification_jobs notification_preferences notifications participant_sessions password_recoveries
platform_settings push_devices record record_event record_file record_segment recording_outbox
user_avatars users
'''.split()


class SourceInventoryTests(unittest.TestCase):
    def setUp(self):
        self.current = json.loads((ROOT / 'installer/v1/release.json').read_bytes())
        self.previous_directory = ROOT / 'installer/legacy/0.6.1-build46'
        self.previous = json.loads((self.previous_directory / 'release.json').read_bytes())

    def test_reviewed_recipe_requires_all_numbered_tables_and_preserves_other_inputs(self):
        expected = {f'database/migrations/{version:06d}_{table}.up.sql'
                    for version, table in enumerate(TABLES, 51)}
        self.assertEqual(53, len(expected))
        current = set(self.current['sourceFiles'])
        current_migrations = {path for path in current if path.startswith('database/migrations/')}
        previous_migrations = {path for path in self.previous['sourceFiles']
                               if path.startswith('database/migrations/')}
        self.assertEqual(expected, current_migrations)
        self.assertEqual(49, len(previous_migrations))
        self.assertTrue(all('/legacy/' in path for path in previous_migrations))
        other_inputs = set(self.previous['sourceFiles']) - previous_migrations
        self.assertEqual(16, len(other_inputs))
        self.assertEqual(other_inputs, current - current_migrations)
        self.assertEqual(69, len(current))
        self.assertTrue(all(re.fullmatch(r'[a-f0-9]{64}', value)
                            for value in self.current['sourceFiles'].values()))

    def test_source_identity_and_dockerfiles_bind_the_same_reviewed_commit(self):
        commit = '6d91abb16a4914a441a9c5e5b5246f940bb4cbf4'
        self.assertEqual(commit, self.current['commit'])
        self.assertEqual('0.6.2-source.' + commit[:12], self.current['version'])
        self.assertEqual('2026-11-08T09:29:43Z', self.current['builtAt'])
        self.assertEqual(r.source_url(commit), self.current['sourceUrl'])
        self.assertEqual('034387d3b5c5512c46af6643c26c2d522b73adda6259c032be48f53dfad2b121',
                         self.current['sourceSha256'])
        for service, dockerfile in self.current['dockerfiles'].items():
            with self.subTest(service=service):
                self.assertEqual(self.current['sourceFiles'][dockerfile['path']], dockerfile['sha256'])
        for policy in ('baseImages', 'images', 'dockerPackages', 'dockerPackagesByUbuntu',
                       'dockerKeySha256', 'sourcePolicy', 'prebuiltPolicy', 'prebuiltRepository'):
            with self.subTest(policy=policy):
                self.assertEqual(self.previous[policy], self.current[policy])

    def test_build46_recovery_bundle_remains_exactly_signed_and_unchanged(self):
        expected = {
            'runner.py': '144333d90e2dff3de2ab16d9d69a4de2a5895a925edf9d2043ce35a60139143b',
            'release.json': 'ce8e782cc82dcbfb314673aff0a37d5e99e9d336750736f9979cb2ef3296093e',
            'release.sig': '4c275f224dd5924f6628cb84870aca762ac0cfbb44b198f953410817b1e3d154',
            'trust-root.pem': '9f16201125cd17dc9fb90a7579e78589c76ecbfdb1ee80df3428ee34d6b90dfc',
            'docker.asc': '1500c1f56fa9e26b9b8f42452a553675796ade0807cdce11975eb98170b3a570',
        }
        bundle = {name: (self.previous_directory / name).read_bytes() for name in expected}
        self.assertEqual(expected, {name: hashlib.sha256(raw).hexdigest() for name, raw in bundle.items()})
        self.assertEqual(self.previous, r.verify_bundle(bundle['runner.py'], bundle['trust-root.pem'],
                                                       bundle['release.json'], bundle['release.sig'], bundle['docker.asc']))
        self.assertEqual(self.previous_directory.joinpath('trust-root.pem').read_bytes(),
                         ROOT.joinpath('installer/v1/trust-root.pem').read_bytes())


if __name__ == '__main__':
    unittest.main()
