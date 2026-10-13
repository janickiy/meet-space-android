#!/usr/bin/env python3
"""Verify current unsigned MeetSpace APK/AABs; write only a stage09 report, never build or publish."""
from pathlib import Path
import argparse
import datetime
import importlib.util
import json
import os
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('meetspace_artifact_checks', ROOT / 'testing/stage08/verify_artifacts.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)
SMOKE_DIRECTORY = ROOT / '.audit/stage09-release-smoke'


def repository_path(value):
    path = Path(value).expanduser()
    return (path if path.is_absolute() else ROOT / path).resolve()


def report_path(value):
    path = repository_path(value)
    # Historical stage08 and stage09 baseline evidence are immutable audit inputs.
    forbidden = ROOT / 'docs/android/evidence/08'
    if path == forbidden or forbidden in path.parents or path.name.startswith(('security-baseline', 'environment-baseline')):
        raise ValueError('Choose a new stage09 verification report path')
    if path.suffix != '.json':
        raise ValueError('Verification report must have a .json suffix')
    return path


def display_path(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='0.9.0')
    parser.add_argument('--version-code', type=int, default=8)
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--bundletool', default=os.environ.get('MEETSPACE_BUNDLETOOL', str(checks.BUNDLETOOL)))
    parser.add_argument('--report', default='docs/android/evidence/09/artifact-verification.json')
    exclusive = parser.add_mutually_exclusive_group()
    exclusive.add_argument('--generate-smoke-apk', action='store_true')
    exclusive.add_argument('--runtime-report', type=Path)
    args = parser.parse_args()
    if not args.java_home or not args.sdk:
        parser.error('Set JAVA_HOME and ANDROID_HOME (or pass --java-home and --sdk)')
    if args.version_code < 1:
        parser.error('--version-code must be positive')
    target = report_path(args.report)
    java_home = repository_path(args.java_home)
    sdk = repository_path(args.sdk)
    bundletool = repository_path(args.bundletool)
    build_tools = sdk / 'build-tools' / args.build_tools
    for executable in [java_home / 'bin' / name for name in ('java', 'jarsigner', 'keytool')] + [build_tools / name for name in ('aapt2', 'apksigner', 'zipalign')]:
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise RuntimeError('Required JDK/Android Build Tools executable is unavailable')
    if not bundletool.is_file() or checks.sha(bundletool.read_bytes()) != checks.BUNDLETOOL_SHA256:
        raise RuntimeError('Use the documented official bundletool with its pinned SHA-256')
    checks.BUNDLETOOL = bundletool
    env = dict(os.environ, JAVA_HOME=str(java_home), LC_ALL='C', LANG='C')
    # jarsigner/apksigner output parsers use their documented English command output.
    # This does not change the parent process's locale or SDK/JDK configuration.
    env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') + ' -Duser.language=en -Duser.country=US').strip()
    needles, inputs = checks.private_needles()
    for marker in ('QualityLifecycleTest', 'CallSignalingLifecycleTest', 'stage09-fixture'):
        needles.add(marker.encode()); needles.add(marker.encode('utf-16le'))
    pattern = re.compile(b'|'.join(re.escape(value) for value in sorted(needles, key=len, reverse=True)))
    artifacts = [
        checks.inspect_apk(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', 'debug', env, build_tools, pattern),
        checks.inspect_apk(ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk', 'release', env, build_tools, pattern),
    ]
    bundle = ROOT / 'app/build/outputs/bundle/release/app-release.aab'
    artifacts.append(checks.inspect_bundle(bundle, env, java_home / 'bin/java', pattern))
    for artifact in artifacts:
        artifact['expectedVersionCodeMatches'] = artifact['manifest']['versionCode'] == args.version_code
        artifact['staticPackagingChecksPassed'] = checks.checks(artifact, args.version) and artifact['expectedVersionCodeMatches']
    result = {
        'schemaVersion': 1,
        'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'expectedApplication': {'package': 'com.meetspace.app', 'versionName': args.version, 'versionCode': args.version_code},
        'tools': {'androidBuildTools': args.build_tools, 'bundletoolVersion': checks.BUNDLETOOL_VERSION,
            'bundletoolSha256': checks.BUNDLETOOL_SHA256},
        'scope': 'Packaging and known-private-material checks of current unsigned release outputs. No publication, permanent signing identity, complete vulnerability scan or runtime compatibility claim.',
        'privateScan': {'knownInputFilesChecked': len(inputs), 'needlesChecked': len(needles),
            'limitation': 'Clean CI has no local private fixture credentials; only available known values and test markers are scanned. This is not an exhaustive secret audit.'},
        'artifacts': artifacts,
        'staticPackagingChecksPassed': all(v['staticPackagingChecksPassed'] for v in artifacts),
        'all64BitGnuRelroEndsAlignedTo16KiB': all(v['nativeLibraries']['all64BitGnuRelroEndsAlignedTo16KiB'] for v in artifacts),
        'runtime16KiBVerified': None,
        'runtimeAssessment': 'Not assessed by this static run.',
        'runtime16KiBVerifiedForAbi': {'arm64-v8a': None, 'x86_64': None},
        'references': {'bundletool': 'https://developer.android.com/tools/bundletool',
            'pageSizes': 'https://developer.android.com/guide/practices/page-sizes',
            'bundletoolRelease': f'https://github.com/google/bundletool/releases/tag/{checks.BUNDLETOOL_VERSION}'},
    }
    if args.generate_smoke_apk:
        result['privateReleaseSmokeApk'] = checks.smoke_apk(bundle, env, java_home / 'bin/java', build_tools, output=SMOKE_DIRECTORY)
    if args.runtime_report:
        result['runtimeEvidence'] = checks.runtime_evidence(repository_path(args.runtime_report), artifacts, env, build_tools,
            smoke=SMOKE_DIRECTORY / 'app-release-smoke.apk')
        result['runtime16KiBVerified'] = True
        result['runtime16KiBVerifiedForAbi']['arm64-v8a'] = True
        result['runtimeAssessment'] = result['runtimeEvidence']['scope']
    result['status'] = 'STATIC_PACKAGING_CHECKS_PASSED' if result['staticPackagingChecksPassed'] else 'STATIC_PACKAGING_CHECK_FAILED'
    if not result['all64BitGnuRelroEndsAlignedTo16KiB']:
        result['status'] += '_WITH_RELRO_FINDINGS'
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + '.tmp')
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(target)
    print(json.dumps({'report': display_path(target), 'status': result['status'],
        'artifacts': [{'variant': v['variant'], 'sha256': v['sha256'], 'checksPassed': v['staticPackagingChecksPassed']} for v in artifacts]}, indent=2))
    return 0 if result['staticPackagingChecksPassed'] else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except checks.BundleManifestError as error:
        print(f'Artifact verification could not finish: {error}', file=sys.stderr)
        sys.exit(2)
    except Exception as error:
        # Neither subprocess output nor private input values belong in an artifact report.
        print(f'Artifact verification could not finish ({type(error).__name__}). Check SDK/JDK, pinned bundletool and expected build outputs.', file=sys.stderr)
        sys.exit(2)
