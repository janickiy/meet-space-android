#!/usr/bin/env python3
"""Read built APK/AABs; never sign or modify deliverables. Optional smoke APK uses a throwaway key."""
from pathlib import Path
import argparse, datetime, hashlib, json, os, re, secrets, shutil, struct, subprocess, sys, tempfile, zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUNDLETOOL_VERSION = '1.18.3'
BUNDLETOOL_SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
BUNDLETOOL = ROOT / f'.audit/tools/bundletool/bundletool-all-{BUNDLETOOL_VERSION}.jar'
BROAD_STORAGE = {'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO', 'android.permission.READ_MEDIA_AUDIO'}
FIREBASE_OFF = ['firebase_messaging_auto_init_enabled', 'firebase_messaging_notification_delegation_enabled',
    'delivery_metrics_exported_to_big_query_enabled', 'firebase_analytics_collection_enabled']
# Publisher notice from com.github.mwiede:jsch:2.28.8, META-INF/LICENSE.txt.
# Pin independently of the source directory: removing or editing both asset and package must fail.
REQUIRED_LICENSE_SHA256 = {
    'jsch-LICENSE.txt': '9c3537ab0b485b391b887b95fdff761de3dd3273a6931e8a1942dde4d2093cc5',
}


def sha(data): return hashlib.sha256(data).hexdigest()
class BundleManifestError(RuntimeError):
    """Safe diagnostics only: no tool output, environment values or manifest content."""


def run(args, env, *, stdout_only=False):
    process = subprocess.run([str(a) for a in args], cwd=ROOT, env=env, capture_output=True, text=True)
    return process.returncode, process.stdout if stdout_only else process.stdout + process.stderr


def xml_nodes(dump):
    nodes, stack = [], []
    for line in dump.splitlines():
        match = re.match(r'( *)E: ([^ ]+)', line)
        if match:
            depth = len(match[1]); node = {'tag': match[2], 'attrs': {}, 'depth': depth}
            while stack and stack[-1]['depth'] >= depth: stack.pop()
            nodes.append(node); stack.append(node)
        elif stack and 'A: ' in line:
            match = re.search(r'A: (?:http://schemas.android.com/apk/res/android:)?([^ (]+)(?:\([^)]*\))?=(.*)', line)
            if match: stack[-1]['attrs'][match[1]] = match[2].split(' (Raw:')[0].strip('"')
    return nodes


def manifest_summary(nodes):
    app = next(n['attrs'] for n in nodes if n['tag'] == 'application')
    manifest = next(n['attrs'] for n in nodes if n['tag'] == 'manifest')
    metadata = {n['attrs'].get('name'): n['attrs'].get('value') for n in nodes if n['tag'] == 'meta-data'}
    services = {n['attrs'].get('name'): n['attrs'] for n in nodes if n['tag'] == 'service'}
    permissions = sorted(n['attrs']['name'] for n in nodes if n['tag'] == 'uses-permission')
    fcm = services.get('com.meetspace.app.MeetSpaceMessagingService', {})
    return {'package': manifest['package'], 'versionName': manifest['versionName'], 'versionCode': int(manifest['versionCode'], 0),
        'allowBackup': app.get('allowBackup') == 'true', 'usesCleartextTraffic': app.get('usesCleartextTraffic') == 'true',
        'debuggable': app.get('debuggable', 'false') == 'true', 'fcmServicePresent': bool(fcm),
        'fcmServiceEnabled': fcm.get('enabled') != 'false', 'fcmServiceExported': fcm.get('exported') != 'false',
        'fcmFallbackServiceAbsent': 'com.google.firebase.messaging.FirebaseMessagingService' not in services,
        'disabledFirebaseMetadata': {k: metadata.get(k) == 'false' for k in FIREBASE_OFF},
        'broadStoragePermissionsAbsent': not BROAD_STORAGE.intersection(permissions), 'permissions': permissions,
        'instrumentationAbsent': not any(n['tag'] == 'instrumentation' for n in nodes)}


def elf_info(data):
    if data[:4] != b'\x7fELF': raise ValueError('Native file is not ELF')
    cls = data[4]; endian = '<' if data[5] == 1 else '>'
    if cls == 2:
        offset = struct.unpack_from(endian + 'Q', data, 32)[0]; entry, count = struct.unpack_from(endian + 'HH', data, 54)
    else:
        offset = struct.unpack_from(endian + 'I', data, 28)[0]; entry, count = struct.unpack_from(endian + 'HH', data, 42)
    loads, relro = [], []
    for index in range(count):
        at = offset + index * entry; kind = struct.unpack_from(endian + 'I', data, at)[0]
        if cls == 2:
            file_offset, virtual = struct.unpack_from(endian + 'QQ', data, at + 8)
            memory, align = struct.unpack_from(endian + 'QQ', data, at + 40)
        else:
            file_offset, virtual = struct.unpack_from(endian + 'II', data, at + 4)
            memory = struct.unpack_from(endian + 'I', data, at + 20)[0]; align = struct.unpack_from(endian + 'I', data, at + 28)[0]
        if kind == 1: loads.append({'alignmentBytes': align, 'compatibleWith16KiB': align >= 16384 and (file_offset - virtual) % 16384 == 0})
        if kind == 0x6474e552: relro.append({'endAddressModulo16KiB': (virtual + memory) % 16384, 'aligned': (virtual + memory) % 16384 == 0})
    return {'loadSegments': loads, 'allLoadSegmentsCompatibleWith16KiB': bool(loads) and all(v['compatibleWith16KiB'] for v in loads),
        'gnuRelroSegments': relro, 'allGnuRelroEndsAlignedTo16KiB': all(v['aligned'] for v in relro)}


def private_needles():
    """Values remain only in memory. Reports contain counts, never private values or match contents."""
    needles, inputs = set(), []
    key_pattern = re.compile(r'password|passwd|secret|token|credential|email|privatekey', re.I)
    def add(value):
        if isinstance(value, str) and len(value) >= 8 and value.lower() not in ('postgres', 'meetspace'):
            needles.add(value.encode()); needles.add(value.encode('utf-16le'))
    def visit(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key_pattern.search(key): add(child)
                visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    for fixture in (ROOT / '.audit').glob('stage0[5678]-fixture'):
        for path in fixture.rglob('*'):
            if not path.is_file(): continue
            if path.name in ('live.properties', 'minio.env', 'fixture-config.json', 'settings.json', 'config.json'):
                inputs.append(path)
                if path.suffix == '.json': visit(json.loads(path.read_text()))
                else:
                    for line in path.read_text().splitlines():
                        if '=' in line and not line.startswith('#'):
                            key, value = line.split('=', 1)
                            if key_pattern.search(key): add(value.strip().strip('"'))
            elif path.suffix == '.pem':
                inputs.append(path); data = path.read_bytes(); needles.add(data)
                for line in data.splitlines():
                    if len(line) >= 48 and not line.startswith(b'-----'): needles.add(line)
    key = ROOT / '.audit/installer-stage04/release-signing-private.pem'
    if key.exists():
        inputs.append(key); needles.add(key.read_bytes())
    # Test-only names must never appear in deliverables, even in a minified application variant.
    for value in ('LiveRecordingsBackend', 'LiveMessagingBackend', 'LiveCallApplication', 'MeetSpaceTestRunner', 'stage08-fixture'):
        needles.add(value.encode()); needles.add(value.encode('utf-16le'))
    return needles, inputs


def required_license_results(archive, prefix):
    results = []
    for filename, expected in sorted(REQUIRED_LICENSE_SHA256.items()):
        name = f'{prefix}assets/licenses/{filename}'
        actual = sha(archive.read(name)) if name in archive.namelist() else None
        results.append({'path': name, 'sha256': actual, 'expectedSha256': expected,
            'matchesPinnedNotice': actual == expected})
    return results


def inspect_zip(path, prefix, needle_regex):
    assets = {f'{prefix}assets/{p.name}': p for p in (ROOT / 'installer/v1').iterdir() if p.is_file()}
    licenses = {f'{prefix}assets/licenses/{p.name}': p for p in (ROOT / 'app/src/main/assets/licenses').iterdir() if p.is_file()}
    native, matches = [], 0
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        private_names = [name for name in names if re.search(r'(?i)(^|/)(?:\.audit|google-services\.json|live\.properties|[^/]*fixture[^/]*|[^/]*(?:private|credentials)[^/]*|[^/]*\.(?:jks|keystore|key))($|/)', name)]
        for name in names:
            data = archive.read(name)
            if needle_regex.search(data): matches += 1
            if name.startswith(prefix + 'lib/') and name.endswith('.so'):
                native.append({'path': name, 'abi': name.split('/')[-2], 'sha256': sha(data),
                    'storedUncompressed': archive.getinfo(name).compress_type == zipfile.ZIP_STORED, **elf_info(data)})
        def asset_results(mapping):
            return [{'path': name, 'sha256': sha(archive.read(name)) if name in names else None,
                'matchesSource': name in names and archive.read(name) == source.read_bytes()} for name, source in sorted(mapping.items())]
        signatures = [name for name in names if re.match(r'META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$', name, re.I)]
        return {'nativeLibraries': {'count': len(native), 'entries': native,
            'all64BitLoadSegmentsCompatibleWith16KiB': all(v['allLoadSegmentsCompatibleWith16KiB'] for v in native if v['abi'] in ('arm64-v8a', 'x86_64')),
            'all64BitGnuRelroEndsAlignedTo16KiB': all(v['allGnuRelroEndsAlignedTo16KiB'] for v in native if v['abi'] in ('arm64-v8a', 'x86_64'))},
            'privateMaterial': {'knownCredentialsCertificatesAndTestClassesAbsent': matches == 0, 'matchedEntryCount': matches,
                'privateFixtureFilesAbsent': not private_names, 'privateFilenameMatchCount': len(private_names),
                'googleServicesJsonAbsent': not any(name.endswith('google-services.json') for name in names)},
            'installerAssets': asset_results(assets), 'thirdPartyLicenses': asset_results(licenses),
            'requiredThirdPartyLicenses': required_license_results(archive, prefix), 'jarSignatureEntriesAbsent': not signatures}


def apk_signing_block(raw):
    eocd = raw.rfind(b'PK\x05\x06')
    if eocd < 0: raise ValueError('Invalid ZIP footer')
    central = struct.unpack_from('<I', raw, eocd + 16)[0]
    return raw[central - 16:central] == b'APK Sig Block 42'


def inspect_apk(path, variant, env, build_tools, needle_regex):
    raw = path.read_bytes(); result = {'variant': variant, 'path': str(path.relative_to(ROOT)), 'bytes': len(raw), 'sha256': sha(raw)}
    code, _ = run([build_tools / 'zipalign', '-c', '-P', '16', '-v', '4', path], env)
    result['zipalign16KiB'] = {'passed': code == 0, 'exitCode': code}
    code, output = run([build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', path], env)
    result['signature'] = {'verified': code == 0, 'expected': 'debug' if variant == 'debug' else 'unsigned',
        'apkSigningBlockPresent': apk_signing_block(raw)}
    if code == 0:
        result['signature']['v2Verified'] = 'Verified using v2 scheme (APK Signature Scheme v2): true' in output
        certificate = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', output)
        if certificate: result['signature']['certificateSha256'] = certificate[1]
        result['signature']['debugCertificate'] = 'CN=Android Debug' in output
    code, dump = run([build_tools / 'aapt2', 'dump', 'xmltree', path, '--file', 'AndroidManifest.xml'], env)
    if code: raise RuntimeError('aapt2 could not read APK manifest')
    result['manifest'] = manifest_summary(xml_nodes(dump))
    result.update(inspect_zip(path, '', needle_regex))
    result['signature']['confirmedUnsigned'] = not result['signature']['verified'] and not result['signature']['apkSigningBlockPresent'] and result['jarSignatureEntriesAbsent']
    return result


def inspect_bundle(path, env, java, needle_regex):
    raw = path.read_bytes(); result = {'variant': 'releaseBundle', 'path': str(path.relative_to(ROOT)), 'bytes': len(raw), 'sha256': sha(raw)}
    code, output = run([java, '-jar', BUNDLETOOL, 'validate', f'--bundle={path}'], env)
    result['bundletoolValidation'] = {'passed': code == 0, 'exitCode': code}
    # JVM locale options may produce a stderr notice; only stdout is the XML document.
    code, manifest = run([java, '-jar', BUNDLETOOL, 'dump', 'manifest', f'--bundle={path}', '--module=base'], env, stdout_only=True)
    if code: raise BundleManifestError(f'bundletool dump manifest failed (exit code {code}).')
    try:
        tree = ET.fromstring(manifest)
    except ET.ParseError as error:
        line, column = error.position
        raise BundleManifestError(f'bundletool manifest stdout is invalid XML (line {line}, column {column}).') from error
    result['manifest'] = manifest_summary([{'tag': node.tag.split('}')[-1], 'attrs': {key.split('}')[-1]: value for key, value in node.attrib.items()}} for node in tree.iter()])
    code, config = run([java, '-jar', BUNDLETOOL, 'dump', 'config', f'--bundle={path}'], env)
    result['bundleRequests16KiBAlignment'] = code == 0 and 'PAGE_ALIGNMENT_16K' in config
    code, signature = run([Path(env['JAVA_HOME']) / 'bin/jarsigner', '-verify', path], env)
    result['signature'] = {'expected': 'unsigned', 'jarIsUnsigned': 'jar is unsigned.' in signature.lower(), 'toolExitCode': code}
    result.update(inspect_zip(path, 'base/', needle_regex))
    result['signature']['confirmedUnsigned'] = result['signature']['jarIsUnsigned'] and result['jarSignatureEntriesAbsent']
    return result


def checks(record, version):
    manifest, private = record['manifest'], record['privateMaterial']
    common = [manifest['package'] == 'com.meetspace.app', manifest['versionName'] == version,
        manifest['fcmServicePresent'], not manifest['fcmServiceEnabled'], not manifest['fcmServiceExported'],
        manifest['fcmFallbackServiceAbsent'], all(manifest['disabledFirebaseMetadata'].values()),
        manifest['broadStoragePermissionsAbsent'], not manifest['allowBackup'], not manifest['usesCleartextTraffic'],
        manifest['instrumentationAbsent'], private['knownCredentialsCertificatesAndTestClassesAbsent'], private['privateFixtureFilesAbsent'],
        all(v['matchesSource'] for v in record['installerAssets'] + record['thirdPartyLicenses']),
        len(record.get('requiredThirdPartyLicenses', [])) == len(REQUIRED_LICENSE_SHA256),
        all(v['matchesPinnedNotice'] for v in record.get('requiredThirdPartyLicenses', [])),
        record['nativeLibraries']['all64BitLoadSegmentsCompatibleWith16KiB']]
    if record['variant'] == 'debug': common += [record['signature']['verified'], record['signature'].get('debugCertificate', False), manifest['debuggable'], record['zipalign16KiB']['passed']]
    else: common += [record['signature']['confirmedUnsigned'], not manifest['debuggable']]
    if record['variant'] == 'release': common += [record['zipalign16KiB']['passed']]
    if record['variant'] == 'releaseBundle': common += [record['bundletoolValidation']['passed'], record['bundleRequests16KiBAlignment']]
    return all(common)


def smoke_apk(bundle, env, java, build_tools, output=None):
    """No permanent release identity is created. Caller owns emulator install/smoke, not this script."""
    output = output or ROOT / '.audit/stage08-release-smoke'; output.mkdir(parents=True, exist_ok=True); output.chmod(0o700)
    with tempfile.TemporaryDirectory(prefix='signing-', dir=output) as tmp:
        tmp = Path(tmp); password = tmp / 'password.txt'; password.write_text(secrets.token_urlsafe(32)); password.chmod(0o600)
        key = tmp / 'smoke.p12'
        code, _ = run([Path(env['JAVA_HOME']) / 'bin/keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
            '-alias', 'smoke', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
            '-dname', 'CN=MeetSpace Temporary Release Smoke', '-storepass:file', password, '-keypass:file', password], env)
        if code: raise RuntimeError('Could not create temporary smoke signing key')
        apks = output / 'release-universal.apks'
        code, _ = run([java, '-jar', BUNDLETOOL, 'build-apks', f'--bundle={bundle}', f'--output={apks}', '--overwrite', '--mode=universal',
            f'--ks={key}', '--ks-key-alias=smoke', f'--ks-pass=file:{password}', f'--key-pass=file:{password}', f'--aapt2={build_tools / "aapt2"}'], env)
        if code: raise RuntimeError('Could not generate smoke APK from unsigned AAB')
        with zipfile.ZipFile(apks) as archive:
            target = output / 'app-release-smoke.apk'; target.write_bytes(archive.read('universal.apk')); target.chmod(0o600)
    code, _ = run([build_tools / 'apksigner', 'verify', target], env)
    if code: raise RuntimeError('Temporary smoke APK signature did not verify')
    return {'path': str(target.relative_to(ROOT)), 'sha256': sha(target.read_bytes()), 'signatureVerified': True,
        'sourceBundleSha256': sha(bundle.read_bytes()), 'temporarySigningKeyDeleted': True,
        'deliverableUnmodified': True, 'installedOrRuntimeTested': False}


def runtime_evidence(path, artifacts, env, build_tools, smoke=None):
    """Bind an explicit test report to these exact packages; do not infer runtime success from static ELF checks."""
    if not path.is_file() or path.stat().st_size > 65536: raise ValueError('Invalid runtime evidence file')
    report = json.loads(path.read_text())
    if report.get('schemaVersion') != 1: raise ValueError('Unsupported runtime evidence schema')
    device = report.get('device', {})
    if device.get('pageSizeBytes') != 16384 or device.get('abi') != 'arm64-v8a' or device.get('androidApi') not in range(35, 100):
        raise ValueError('Runtime evidence must describe an actual 16KiB ARM64 system')
    if device.get('linkerCompatibilityEnabled') is not False or device.get('packageManagerCompatibilityDisabled') is not True:
        raise ValueError('Runtime evidence must disable both compatibility modes')
    application = report.get('application', {})
    expected_app = {'packageName': 'com.meetspace.app', 'versionName': artifacts[0]['manifest']['versionName'],
        'versionCode': artifacts[0]['manifest']['versionCode']}
    if application != expected_app: raise ValueError('Runtime package identity/version mismatch')
    artifact_hashes = report.get('artifacts', {})
    keys = {'debug': 'debugApkSha256', 'release': 'releaseApkSha256', 'releaseBundle': 'releaseBundleSha256'}
    for artifact in artifacts:
        if artifact_hashes.get(keys[artifact['variant']]) != artifact['sha256']: raise ValueError('Runtime evidence is for another build')
        if artifact['manifest']['package'] != expected_app['packageName'] or artifact['manifest']['versionName'] != expected_app['versionName'] or artifact['manifest']['versionCode'] != expected_app['versionCode']:
            raise ValueError('Built artifact identities disagree')
    smoke = smoke or ROOT / '.audit/stage08-release-smoke/app-release-smoke.apk'
    if not smoke.is_file() or sha(smoke.read_bytes()) != artifact_hashes.get('releaseSmokeApkSha256'):
        raise ValueError('Runtime smoke APK hash mismatch')
    code, _ = run([build_tools / 'apksigner', 'verify', smoke], env)
    if code: raise ValueError('Runtime smoke APK signature did not verify')
    code, dump = run([build_tools / 'aapt2', 'dump', 'xmltree', smoke, '--file', 'AndroidManifest.xml'], env)
    if code: raise ValueError('Cannot inspect runtime smoke APK')
    manifest = manifest_summary(xml_nodes(dump))
    if manifest['package'] != application['packageName'] or manifest['versionName'] != application['versionName'] or manifest['versionCode'] != application['versionCode'] or manifest['debuggable']:
        raise ValueError('Smoke APK must be the matching nondebuggable release')
    # Signed APK identity alone cannot prove it was produced from the currently inspected AAB.
    # Bind its executable DEX, native libraries and application assets to the bundle as well.
    bundle = ROOT / next(v['path'] for v in artifacts if v['variant'] == 'releaseBundle')
    compared = 0
    with zipfile.ZipFile(bundle) as source, zipfile.ZipFile(smoke) as apk:
        for name in source.namelist():
            if name.startswith('base/dex/') and name.endswith('.dex'): target = name.removeprefix('base/dex/')
            elif name.startswith(('base/lib/', 'base/assets/')) and not name.endswith('/'): target = name.removeprefix('base/')
            else: continue
            if target not in apk.namelist() or sha(source.read(name)) != sha(apk.read(target)):
                raise ValueError('Smoke APK executable content differs from current AAB')
            compared += 1
    if compared == 0: raise ValueError('No smoke content was bound to AAB')
    debug = report.get('debug', {})
    required_libraries = ['androidx.graphics.path', 'datastore_shared_counter', 'jingle_peerconnection_so']
    if sorted(debug.get('nativeLibrariesLoaded', [])) != sorted(required_libraries): raise ValueError('Missing native library load evidence')
    if debug.get('webRtcFactoryCreatedAndDisposed') is not True or debug.get('recordingPlaybackPassed') is not True:
        raise ValueError('Missing WebRTC factory or recording playback runtime evidence')
    release = report.get('releaseSmoke', {})
    if release.get('launchPassed') is not True: raise ValueError('Missing release launch evidence')
    summary = {'abi': 'arm64-v8a', 'pageSizeBytes': 16384, 'androidApi': device['androidApi'],
        'linkerCompatibilityEnabled': False, 'packageManagerCompatibilityDisabled': True,
        'application': expected_app, 'artifacts': {key: artifact_hashes[key] for key in [*keys.values(), 'releaseSmokeApkSha256']},
        'debug': {'nativeLibrariesLoaded': required_libraries, 'webRtcFactoryCreatedAndDisposed': True, 'recordingPlaybackPassed': True},
        'releaseSmoke': {'launchPassed': True}, 'smokeDexNativeAndAssetsMatchCurrentBundle': True,
        'bundleEntriesCompared': compared,
        'evidenceSha256': sha(path.read_bytes()),
        'scope': 'ARM64 only: debug JNI loads for three libraries, WebRTC factory create/dispose, and recording playback; release APK from current AAB launches without test CA. API36 graphics-path UI uses the system PathIterator, so its native path-iteration operations are not established by this test. x86_64 and all other native operations/devices remain unverified.'}
    if isinstance(release.get('recordingPlaybackPassed'), bool): summary['releaseSmoke']['recordingPlaybackPassed'] = release['recordingPlaybackPassed']
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='0.8.0')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME', str(ROOT / '.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home')))
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--generate-smoke-apk', action='store_true')
    parser.add_argument('--runtime-report', type=Path, help='Explicit hash-bound 16KiB ARM64 test evidence JSON; never combines with smoke regeneration')
    args = parser.parse_args()
    if args.generate_smoke_apk and args.runtime_report: parser.error('--runtime-report cannot be combined with --generate-smoke-apk')
    if not BUNDLETOOL.exists() or sha(BUNDLETOOL.read_bytes()) != BUNDLETOOL_SHA256: raise RuntimeError('Install the documented pinned bundletool JAR before verification')
    env = dict(os.environ, JAVA_HOME=args.java_home); java = Path(args.java_home) / 'bin/java'; build_tools = Path(args.sdk) / 'build-tools' / args.build_tools
    needles, inputs = private_needles(); pattern = re.compile(b'|'.join(re.escape(value) for value in sorted(needles, key=len, reverse=True)))
    artifacts = [inspect_apk(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', 'debug', env, build_tools, pattern),
        inspect_apk(ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk', 'release', env, build_tools, pattern)]
    bundle = ROOT / 'app/build/outputs/bundle/release/app-release.aab'; artifacts.append(inspect_bundle(bundle, env, java, pattern))
    for record in artifacts: record['staticPackagingChecksPassed'] = checks(record, args.version)
    result = {'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'tools': {'androidBuildTools': args.build_tools, 'bundletoolVersion': BUNDLETOOL_VERSION, 'bundletoolSha256': BUNDLETOOL_SHA256},
        'scope': 'Artifact packaging checks; no Play upload, permanent signing identity, physical-device or runtime compatibility claim. Known local credentials/certificates and test class markers checked; not an exhaustive secret audit.',
        'privateScan': {'knownInputFilesChecked': len(inputs), 'needlesChecked': len(needles)},
        'artifacts': artifacts, 'staticPackagingChecksPassed': all(v['staticPackagingChecksPassed'] for v in artifacts),
        'all64BitGnuRelroEndsAlignedTo16KiB': all(v['nativeLibraries']['all64BitGnuRelroEndsAlignedTo16KiB'] for v in artifacts),
        'runtime16KiBVerified': None, 'runtimeAssessment': 'Not assessed by this static run.',
        'runtime16KiBVerifiedForAbi': {'arm64-v8a': None, 'x86_64': None},
        'references': {'bundletool': 'https://developer.android.com/tools/bundletool', 'pageSizes': 'https://developer.android.com/guide/practices/page-sizes',
            'bundletoolRelease': f'https://github.com/google/bundletool/releases/tag/{BUNDLETOOL_VERSION}'}}
    if args.generate_smoke_apk: result['privateReleaseSmokeApk'] = smoke_apk(bundle, env, java, build_tools)
    if args.runtime_report:
        result['runtimeEvidence'] = runtime_evidence(args.runtime_report, artifacts, env, build_tools)
        result['runtime16KiBVerified'] = True
        result['runtime16KiBVerifiedForAbi']['arm64-v8a'] = True
        result['runtimeAssessment'] = result['runtimeEvidence']['scope']
    result['status'] = 'STATIC_PACKAGING_CHECKS_PASSED' if result['staticPackagingChecksPassed'] else 'STATIC_PACKAGING_CHECK_FAILED'
    if not result['all64BitGnuRelroEndsAlignedTo16KiB']: result['status'] += '_WITH_RELRO_FINDINGS'
    target = ROOT / 'docs/android/evidence/08/artifact-verification.json'; target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'report': str(target.relative_to(ROOT)), 'status': result['status'],
        'artifacts': [{'variant': v['variant'], 'sha256': v['sha256'], 'bytes': v['bytes'], 'checksPassed': v['staticPackagingChecksPassed']} for v in artifacts],
        'privateReleaseSmokeApk': result.get('privateReleaseSmokeApk')}, indent=2))
    return 0 if result['staticPackagingChecksPassed'] else 1

if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as error:
        # No subprocess output or private input content is ever included in an error report.
        print(f'Artifact verification could not finish ({type(error).__name__}).', file=sys.stderr); sys.exit(2)
