"""The ready-image path freezes GitHub assets and never falls back to compilation."""
import contextlib
import copy
import io
import json
import pathlib
import tarfile
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

from test_runner import ROOT, r


class PrebuiltTests(unittest.TestCase):
    def setUp(self):
        self.recipe = json.loads((ROOT / 'installer/v1/release.json').read_bytes())
        self.tag = 'v0.4.0-prebuilt.test.1'
        self.commit = 'd' * 40
        self.arch = 'arm64'
        self.url = f'https://github.com/{r.REPOSITORY}/releases/download/{self.tag}/meetspace-{self.tag}-linux-{self.arch}.tar.gz'
        self.source = dict(repository=r.REPOSITORY, channel='prebuilt', ref=self.tag, version=self.tag,
                           commit=self.commit, builtAt='2026-10-23T00:00:00Z', sourceUrl=r.source_url(self.commit),
                           sourceSha256='c' * 64, sourceFiles=self.recipe['sourceFiles'])
        self.lock = dict(repository=r.REPOSITORY, version=self.tag, commit=self.commit, platform='linux/' + self.arch,
                         url=self.url, sha256='a' * 64, sizeBytes=1024, indexSha256='b' * 64)
        self.options = dict(domain='meet.example.com', publicIpv4='8.8.8.8', imageMode='images', imageRelease='latest')
        self.index = dict(schemaVersion=1, installationMode='prebuilt', repository=r.REPOSITORY, version=self.tag,
                          commit=self.commit, platforms={'linux/' + self.arch: {k: self.lock[k] for k in ('url', 'sha256', 'sizeBytes')}})
        self.release = dict(tag_name=self.tag, draft=False, prerelease=True, published_at='2026-10-23T00:00:00Z', assets=[
            dict(name='prebuilt.json', state='uploaded', size=800,
                 browser_download_url=f'https://github.com/{r.REPOSITORY}/releases/download/{self.tag}/prebuilt.json'),
            dict(name=f'meetspace-{self.tag}-linux-{self.arch}.tar.gz', state='uploaded', size=1024,
                 browser_download_url=self.url, digest='sha256:' + 'a' * 64)])

    def rejected(self, code, function, *args, **kwargs):
        with self.assertRaises(r.InstallError) as caught:
            function(*args, **kwargs)
        self.assertEqual(code, caught.exception.code)

    def test_modes_keep_legacy_source_default_and_reject_unknown_inputs(self):
        self.assertEqual('source', r.image_mode({}))
        for mode in ('source', 'images'):
            self.assertEqual(mode, r.image_mode({'imageMode': mode}))
        for mode in ('auto', '', None, True, {}, []):
            self.rejected('INVALID_IMAGE_MODE', r.image_mode, {'imageMode': mode})
        for options in (dict(self.options, imageRelease='--help'), dict(self.options, imageMode='source')):
            self.rejected('INVALID_IMAGE_MODE', r.validate_options, options, self.recipe)

    def test_ready_images_reuse_docker_without_requiring_a_buildx_plugin(self):
        with patch.object(r.shutil, 'which', return_value='/usr/bin/docker'), \
             patch.object(r, 'run', side_effect=[b'29.0.0', b'2.40.0']) as execute:
            facts = r.docker_facts(building=False)
        self.assertFalse(r.docker_install_required(facts))
        self.assertEqual(['reuse-docker'], r.docker_actions(facts))
        self.assertFalse(any('buildx' in call.args[0] for call in execute.call_args_list))

    def test_only_complete_published_architecture_packages_qualify(self):
        self.assertIsNotNone(r.prebuilt_assets(self.release, 'arm64'))
        self.assertIsNone(r.prebuilt_assets(self.release, 'amd64'))
        for change in (dict(draft=True), dict(assets=[]), dict(published_at='invalid'), dict(assets=self.release['assets'] * 2)):
            self.assertIsNone(r.prebuilt_assets(dict(self.release, **change), self.arch))

    def resolve(self, releases=None, index=None):
        responses = [(releases if releases is not None else [self.release], 'e' * 64),
                     (self.index if index is None else index, self.lock['indexSha256'])]
        commits = [{'sha': self.commit, 'commit': {'committer': {'date': self.source['builtAt']}}}]
        with patch.object(r, 'asset_json', side_effect=responses), patch.object(r, 'github_json', return_value=commits), \
             patch.object(r, 'source_snapshot', return_value=(self.source['sourceSha256'], self.source['sourceFiles'])) as snapshot:
            result = r.resolve_prebuilt(self.recipe, self.arch)
            snapshot.assert_called_once_with(self.commit, self.recipe, building=False)
            return result

    def test_latest_chooses_prerelease_with_images_over_newer_source_only_release(self):
        newer = dict(self.release, tag_name='v9.0.0', prerelease=False, published_at='2026-10-24T00:00:00Z', assets=[])
        source, lock = self.resolve([newer, self.release])
        self.assertEqual(self.source, source)
        self.assertEqual(self.lock, lock)

    def test_missing_platform_never_builds_or_uses_source_release(self):
        with patch.object(r, 'asset_json', return_value=([dict(self.release, assets=[])], 'e' * 64)), \
             patch.object(r, 'resolve_source') as source, patch.object(r, 'run') as execute:
            self.rejected('PREBUILT_UNAVAILABLE', r.resolve_prebuilt, self.recipe, self.arch)
            source.assert_not_called(); execute.assert_not_called()

    def test_network_failures_never_trigger_source_fallback(self):
        for status, expected in ((403, 'SOURCE_RATE_LIMIT'), (429, 'SOURCE_RATE_LIMIT'), (500, 'PREBUILT_DOWNLOAD_FAILED')):
            with patch.object(r, 'asset_json', side_effect=urllib.error.HTTPError(self.url, status, '', {}, None)), \
                 patch.object(r, 'resolve_source') as source:
                self.rejected(expected, r.resolve_prebuilt, self.recipe, self.arch)
                source.assert_not_called()

    def test_asset_metadata_must_match_github_size_and_digest(self):
        for key, value in (('sizeBytes', 1025), ('sha256', 'f' * 64), ('url', 'https://attacker.test/images.tar.gz')):
            index = copy.deepcopy(self.index)
            index['platforms']['linux/' + self.arch][key] = value
            with self.assertRaises(r.InstallError):
                self.resolve(index=index)

    def test_image_lock_rejects_architecture_commit_url_and_policy_changes(self):
        r.validate_image_lock(self.lock, self.recipe, self.source, self.arch)
        for key, value in (('platform', 'linux/amd64'), ('commit', 'e' * 40), ('version', 'v9.0.0'), ('sha256', 'bad'),
                           ('sizeBytes', True), ('sizeBytes', 3 * 1024**3), ('url', self.url + '?redirect=1')):
            self.rejected('PLAN_CHANGED', r.validate_image_lock, dict(self.lock, **{key: value}), self.recipe, self.source, self.arch)
        self.rejected('PLAN_CHANGED', r.validate_image_lock, self.lock, dict(self.recipe, prebuiltPolicy='unknown'), self.source, self.arch)
        self.rejected('PLAN_CHANGED', r.validate_image_lock, self.lock, self.recipe, self.source, self.arch, requested='v9.0.0')
        r.validate_image_lock(self.lock, self.recipe, self.source, self.arch, requested=self.tag)

    def plan(self):
        facts = dict(os='ubuntu', osVersion='22.04', arch=self.arch, cpus=2, memoryKiB=4 * 1024**2)
        with patch.object(r, 'platform_facts', return_value=facts), patch.object(r, 'managed_marker', return_value=None), \
             patch.object(r, 'disk_free', return_value=20 * 1024**3), patch.object(r, 'docker_facts', return_value={'installed': False}), \
             patch.object(r, 'check_ports'), patch.object(r, 'dns_check', return_value=['8.8.8.8']), \
             patch.object(r.pathlib.Path, 'exists', return_value=False):
            return r.create_plan(self.options, self.recipe, source_lock=self.source, image_lock=self.lock, issued_at=123)

    def test_frozen_plan_binds_mode_and_archive_without_resolving_latest_again(self):
        plan = self.plan()
        self.assertIn('download-verify-load-images', plan['actions'])
        self.assertNotIn('build-and-pin-images', plan['actions'])
        with patch.object(r, 'resolve_prebuilt') as lookup:
            self.assertEqual(self.commit, r.manifest_for_plan(self.recipe, plan)['commit'])
            lookup.assert_not_called()
        for field in ('imageLock', 'sourceLock'):
            changed = copy.deepcopy(plan); changed.pop(field)
            changed['hash'] = r.digest(r.canonical({k: v for k, v in changed.items() if k != 'hash'}))
            self.rejected('PLAN_CHANGED', r.manifest_for_plan, self.recipe, changed)
        changed = copy.deepcopy(plan); changed['options']['imageMode'] = 'source'
        changed['hash'] = r.digest(r.canonical({k: v for k, v in changed.items() if k != 'hash'}))
        self.rejected('PLAN_CHANGED', r.manifest_for_plan, self.recipe, changed)

    def test_redirects_allow_only_https_github_asset_hosts(self):
        r.check_asset_url('https://release-assets.githubusercontent.com/path?signature=opaque')
        for url in ('http://github.com/a', 'https://github.com.evil.test/a', 'https://user:password@github.com/a', 'https://127.0.0.1/a', 'https://github.com:8443/a'):
            self.rejected('PREBUILT_INVALID', r.check_asset_url, url)

    def test_cancelled_or_corrupt_download_never_leaves_an_importable_archive(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(r, 'no_symlink'):
            path = pathlib.Path(temp) / 'images.tar.gz'
            for data in (b'bad', b'x' * 1025):
                with patch.object(r, 'asset_response', return_value=io.BytesIO(data)), self.assertRaises(r.InstallError):
                    r.download_prebuilt(self.lock, path)
                self.assertFalse(path.exists()); self.assertFalse(path.with_suffix('.partial').exists())
            with patch.object(r, 'asset_response', return_value=io.BytesIO(b'payload')):
                self.rejected('CANCELLED', r.download_prebuilt, self.lock, path, lambda: r.fail('CANCELLED'))
            self.assertFalse(path.exists()); self.assertFalse(path.with_suffix('.partial').exists())

    def test_unpack_rejects_traversal_links_duplicates_and_special_modes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            for count, (name, kind, mode, duplicate) in enumerate([
                ('bundle/../../escape', tarfile.REGTYPE, 0o644, False), ('/bundle/file', tarfile.REGTYPE, 0o644, False),
                ('bundle/link', tarfile.SYMTYPE, 0o644, False), ('bundle/file', tarfile.REGTYPE, 0o4755, False),
                ('bundle/file', tarfile.REGTYPE, 0o644, True)]):
                archive = root / f'{count}.tar.gz'
                with tarfile.open(archive, 'w:gz') as tar:
                    member = tarfile.TarInfo(name); member.type = kind; member.mode = mode; member.linkname = '/etc'
                    tar.addfile(member)
                    if duplicate: tar.addfile(member)
                self.rejected('PREBUILT_INVALID', r.extract_prebuilt, archive, root / f'extract-{count}')
            self.assertFalse((root / 'escape').exists())

    @contextlib.contextmanager
    def package(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(r, 'no_symlink'):
            root = pathlib.Path(temp); bundle = root / 'bundle'; release = bundle / 'release'; release.mkdir(parents=True)
            manifest = dict(schemaVersion=1, version=self.tag, commit=self.commit, platform='linux/' + self.arch,
                artifactMode='archive', builtAt='2026-10-23T00:00:00Z', sourceDirty=False, sourceFingerprintVerified=True,
                securityScanned=True, sourceSHA256='c' * 64, deploymentLayout='hardened-shared-host', objectStorageProvider='seaweedfs',
                images={}, imageConfigIDs={}, securityEvidence={})
            with tarfile.open(release / 'images.tar', 'w') as tar:
                for service in r.PREBUILT_SERVICES:
                    labels = {'org.opencontainers.image.version': self.tag, 'org.opencontainers.image.revision': self.commit,
                              'io.go-recorder.source-sha256': manifest['sourceSHA256'], 'test-service': service}
                    config = r.canonical(dict(architecture=self.arch, os='linux', config=dict(Labels=labels)))
                    config_id = 'sha256:' + r.digest(config)
                    descriptor = r.canonical(dict(schemaVersion=2, config=dict(digest=config_id), layers=[]))
                    image = 'sha256:' + r.digest(descriptor)
                    for data, identifier in ((config, config_id), (descriptor, image)):
                        member = tarfile.TarInfo('blobs/sha256/' + identifier[7:]); member.size = len(data); tar.addfile(member, io.BytesIO(data))
                    scan = r.canonical(dict(SchemaVersion=2, Metadata=dict(ImageID=config_id, ImageConfig=dict(architecture=self.arch)), Results=[]))
                    sbom = r.canonical(dict(bomFormat='CycloneDX'))
                    (release / (service + '.scan.json')).write_bytes(scan); (release / (service + '.sbom.json')).write_bytes(sbom)
                    manifest['images'][service] = image; manifest['imageConfigIDs'][service] = config_id
                    manifest['securityEvidence'][service] = dict(scanSHA256=r.digest(scan), sbomSHA256=r.digest(sbom))
                index = r.canonical({'schemaVersion': 2, 'manifests': [{'digest': image} for image in manifest['images'].values()]})
                member = tarfile.TarInfo('index.json'); member.size = len(index); tar.addfile(member, io.BytesIO(index))
            manifest['archiveSHA256'] = r.file_digest(release / 'images.tar')
            self.write_manifest(bundle, manifest)
            yield root, bundle, manifest

    def write_manifest(self, bundle, manifest):
        (bundle / 'release/release.json').write_bytes(r.canonical(manifest))
        (bundle / 'bundle.json').write_bytes(r.canonical({'manifestSHA256': r.digest(r.canonical(manifest))}))

    def test_package_checks_architecture_provenance_scans_and_sbom(self):
        with self.package() as (_, bundle, manifest):
            self.assertEqual(manifest, r.verify_prebuilt_package(bundle, self.lock))
            for key, value in (('commit', 'e' * 40), ('securityScanned', False), ('sourceDirty', True), ('platform', 'linux/amd64')):
                self.write_manifest(bundle, dict(manifest, **{key: value}))
                self.rejected('PREBUILT_INVALID', r.verify_prebuilt_package, bundle, self.lock)
            self.write_manifest(bundle, manifest)
            scan = bundle / 'release/api.scan.json'
            content = json.loads(scan.read_bytes()); content['Results'] = [{'Vulnerabilities': [{'Severity': 'HIGH'}]}]
            scan.write_bytes(r.canonical(content)); manifest['securityEvidence']['api']['scanSHA256'] = r.file_digest(scan)
            self.write_manifest(bundle, manifest)
            self.rejected('PREBUILT_SECURITY', r.verify_prebuilt_package, bundle, self.lock)

    def test_archive_or_scan_tampering_rejected(self):
        for name in ('images.tar', 'api.scan.json', 'api.sbom.json'):
            with self.package() as (_, bundle, _):
                with (bundle / 'release' / name).open('ab') as output: output.write(b'tampered')
                self.rejected('RELEASE_CHECKSUM', r.verify_prebuilt_package, bundle, self.lock)

    def test_real_worker_import_and_resume_never_build_or_change_snapshot(self):
        with self.package() as (root, bundle, manifest), patch.object(r, 'state_write'):
            worker = object.__new__(r.Worker)
            worker.directory = root; worker.images_file = root / 'images.json'
            worker.options = self.options; worker.plan = dict(imageLock=self.lock, resourceProfile='compact-test')
            worker.manifest = self.recipe
            bundle.rename(root / 'prebuilt')
            (root / 'prebuilt.tar.gz').write_bytes(b'a' * 1024)
            lock = dict(self.lock, sha256=r.file_digest(root / 'prebuilt.tar.gz'))
            worker.plan['imageLock'] = lock
            references = {identifier: service for service, identifier in manifest['images'].items()}
            references[self.recipe['images']['certbot']] = 'certbot'
            def inspect(reference):
                service = references[reference]
                return {'Id': 'sha256:' + r.digest(service.encode()), 'Architecture': self.arch, 'Config': {'Labels': {
                    'org.opencontainers.image.version': self.tag, 'org.opencontainers.image.revision': self.commit,
                    'io.go-recorder.source-sha256': manifest['sourceSHA256']}}}
            with patch.object(r, 'disk_free', return_value=20 * 1024**3), patch.object(r, 'run', return_value=b'') as execute, \
                 patch.object(worker, 'image_details', side_effect=inspect), patch.object(r, 'resolve_prebuilt') as resolve:
                worker.phase_images()
                self.assertEqual(['load', 'pull'], [call.args[0][1] for call in execute.call_args_list])
                self.assertEqual(13, len(r.read_json(worker.images_file)))  # 12 deployed + certbot
                execute.reset_mock()
                worker.phase_images()
                execute.assert_not_called(); resolve.assert_not_called()
                self.assertEqual(lock, worker.plan['imageLock'])


if __name__ == '__main__':
    unittest.main()
