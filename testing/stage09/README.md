# Stage09 verification and CI

The workflow and verifier are prepared locally. **No GitHub Actions run is claimed.** Local validation passed: eight artifact-verifier regression tests, Python syntax, YAML parsing, `bash -n` for all five shell steps, and actionlint1.7.12 (without unavailable shellcheck/pyflakes integrations). Verifier regressions include separating bundletool manifest stdout from JVM stderr notices and safe malformed-output diagnostics. This workspace must first be committed to the Android repository at its root; a workflow file outside a GitHub repository does not run automatically.

## Portable artifact verification

Requirements on Linux/macOS: Python 3.9+, JDK 17, Android Build Tools 35.0.0, the current debug APK, unsigned release APK and release AAB, and official bundletool 1.18.3. No macOS-only JDK/SDK path is assumed by the stage09 wrapper. Native Windows executable suffixes are not implemented; use a matching Linux SDK/JDK inside WSL or a Linux CI runner.

Set `JAVA_HOME` and `ANDROID_HOME` (`ANDROID_SDK_ROOT` also accepted), or pass `--java-home` and `--sdk`. `--bundletool PATH` / `MEETSPACE_BUNDLETOOL` chooses the JAR, whose contents must match SHA-256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`. Default JAR location is `.audit/tools/bundletool/bundletool-all-1.18.3.jar`. Download it from the [official release](https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar); never disable the hash check.

```sh
python3 -m unittest discover -s testing/stage09 -p 'test_*.py' -v
python3 testing/stage09/verify_artifacts.py
```

Default expected identity is `com.meetspace.app`, **0.9.0 (8)**. `--version` and `--version-code` support a deliberate later release. Outputs are read at ordinary Gradle paths under `app/build/outputs`. The default report is `docs/android/evidence/09/artifact-verification.json`; CI passes `--report build/reports/stage09/artifact-verification.json`. Historical stage08 reports and named stage09 baseline reports are rejected as output targets.

The wrapper reuses stage08 parsing, actual signing/manifest/ELF checks, private-material scans, installer asset hashes and license verification. JSch BSD notice is independently pinned, so deleting it from both source and package fails verification. The unsigned expectation is intentional: use the separate [release signing instructions](../../docs/android/08_RELEASE.md) for distribution. CI does not create a release identity, sign for distribution or publish.

Optional `--generate-smoke-apk` writes only `.audit/stage09-release-smoke/app-release-smoke.apk` with a temporary signing key that is deleted. It does not install to an emulator or change the unsigned artifacts. Optional `--runtime-report PATH` accepts the [stage08 hash-bound evidence schema](../stage08/README.md#strict-16-kib-runtime-verification), but checks the **stage09** smoke APK/current artifacts. These options are mutually exclusive. Regenerating a smoke APK changes its certificate/hash and requires new runtime evidence.

No runtime report means `runtime16KiBVerified=null`, not success or failure. Formal GNU_RELRO findings remain visible. A static passing result does not establish every native operation, x86_64 16 KiB compatibility, or Play publication eligibility.

## Reproduce local measurements

These are commands for a **new audit in an isolated workspace and an emulator created for that audit**. They are not recorded results. Existing `docs/android/evidence/09/baseline-*`, `after-*`, `android-runs/*` and `measurement-summary.json` are historical inputs: the helpers write those names directly, so do not rerun them over this audit's evidence. Use a separate workspace with a new evidence directory. Keep one Gradle process and one instrumentation process per workspace/device; never run these commands while another test or measurement owns the emulator.

Set `JAVA_HOME` and `ANDROID_HOME`, then select the actual owned emulator serial explicitly. Checking the `emulator-` prefix alone does not establish ownership; confirm its AVD name against the AVD created for this audit. Most measurement helpers do not enforce ownership themselves.

```sh
qualitySerial="${MEETSPACE_QUALITY_SERIAL:?Set the serial of your disposable audit emulator}"
qualityAdb="$ANDROID_HOME/platform-tools/adb"
case "$qualitySerial" in emulator-*) ;; *) exit 1 ;; esac
"$qualityAdb" -s "$qualitySerial" emu avd name
"$qualityAdb" -s "$qualitySerial" shell getprop ro.build.fingerprint
"$qualityAdb" -s "$qualitySerial" shell getprop ro.product.cpu.abi
"$qualityAdb" -s "$qualitySerial" shell getconf PAGE_SIZE
umask 077
mkdir -p .audit/stage09 docs/android/evidence/09/android-runs
chmod 700 .audit/stage09
```

Record image revision, API/ABI/page size, GPU mode, actual RAM/heap limits, resolution/density/font scale and animation settings before both runs. This audit's comparison uses API36 ARM64, 4 KiB pages and a software GPU; do not merge its numbers with another device configuration. Neither `profile_startup.py` nor the lifecycle helpers configure a comparable device for you. The fixture helpers and host process observer are local audit tools: fixture prerequisites include the documented Go/PostgreSQL/Docker/OpenSSL setup, and `observe_call_cycles.py` uses macOS `ps -M` and `lsof`. Their host counters are not portable Linux CI measurements.

### Freeze the baseline before production fixes

Save the app APK, exact test APK, source snapshot, resolved release graph and hashes **before changing production code**. For a future audit starting from its unfixed source, the initial build/copy is:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
test ! -e .audit/stage09/baseline-app-debug.apk
test ! -e .audit/stage09/baseline-app-debug-androidTest.apk
cp app/build/outputs/apk/debug/app-debug.apk .audit/stage09/baseline-app-debug.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk .audit/stage09/baseline-app-debug-androidTest.apk
```

This does not recreate this audit's old baseline from the now-fixed source. The existing frozen `baseline-app-debug.apk` is the pre-fix application. If a measurement-only test APK must be recompiled, retain both test APKs and their hashes, then install the frozen app and the deliberately selected compatible test APK separately. `:app:assembleDebugAndroidTest` can build app intermediates; do not replace the frozen app with that output. Avoid `connectedDebugAndroidTest`/`installDebug` during a frozen-baseline measurement because they can install the newly built target app. Both APKs need matching debug signing identity. A version/signature mismatch must be resolved only on the disposable emulator, never by deleting a user's installation.

```sh
qualityApp=.audit/stage09/baseline-app-debug.apk
qualityTest=.audit/stage09/baseline-app-debug-androidTest.apk
"$qualityAdb" -s "$qualitySerial" install -r -d "$qualityApp"
"$qualityAdb" -s "$qualitySerial" install -r -d "$qualityTest"
"$qualityAdb" -s "$qualitySerial" shell dumpsys package com.meetspace.app > .audit/stage09/baseline-installed-package.txt
python3 - "$qualityApp" "$qualityTest" <<'PY'
import hashlib, json, pathlib, sys
print(json.dumps({name: hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest() for name in sys.argv[1:]}, indent=2))
PY
```

Record the selected APK pair with the run. The lifecycle JSON obtains the installed app's `versionName` through `PackageManager`, rather than the separately compiled test's `BuildConfig`. Version metadata alone is not a binary identity: retain APK SHA-256 values as well. `profile_startup.py` hashes the supplied `--apk` file; it does not independently prove that this file is installed. Install/check the exact file immediately before profiling.

### Startup and fixture preparation

`profile_startup.py` force-stops and explicitly launches the app nine times without clearing data. It excludes two warmup launches and summarizes seven `am start -W TotalTime` samples. This is process-cold **first-display** timing; it is not fully-drawn time, a cold OS/page-cache benchmark, a network measurement or physical battery evidence. Use the same public app state for baseline and after, with no test instrumentation running.

```sh
python3 testing/stage09/profile_startup.py \
  --serial "$qualitySerial" --adb "$qualityAdb" --label baseline \
  --apk .audit/stage09/baseline-app-debug.apk --output docs/android/evidence/09
```

Create only disposable fixtures using [stage06](../stage06/README.md) and [stage08](../stage08/README.md), including their prerequisites and ownership checks. The unchanged pinned backend checkout must exist at the chosen source path. These commands start real fixture services, not production:

```sh
python3 testing/stage06/fixture.py start --backend-source .audit/meet-space-stage04
python3 testing/stage06/run_peers.py --count 1
python3 testing/stage08/fixture.py start --backend-source .audit/meet-space-stage04
python3 testing/stage08/run_contract.py
```

Before using the call observer, verify the stage06 fixture is ready and has a real idle peer plus current `pid.json`, `snapshot.json` and `runtime-snapshot.json`. The peer helper writes the meeting into its private `live.properties`; do not copy those inputs before it is ready. The stage08 contract must finish to provide real recording/file IDs. No measurement command creates a production account or uses personal credentials.

`copy_fixture_inputs.py` force-stops the app, removes only the three known disposable fixture marker/CA pairs from its private `files` directory, then copies one pair via stdin with mode0600. It converts the API origin from host loopback to Android emulator `10.0.2.2`. It does not disable TLS verification or clear all app data. It requires a debuggable app with `run-as`, default fixture homes/ports, and already-created private input files; `--stage 6`, `7` and `8` select call, messaging and recording fixtures respectively. Restart instrumentation after every change of fixture.

### Lifecycle workloads

`QualityLifecycleTest` is deliberately expensive and opt-in (`-e quality09 true`) and also requires the appropriate fixture marker. Missing opt-in or fixture input produces a skipped test, not successful coverage. Each helper launches a fresh instrumentation process and checks both completed JSON and an actual `OK (1 test)` runner result. Run baseline first; after narrow production fixes install the exact new normal debug app/test pair, record its hashes, and repeat with `--label after` under the same conditions.

| Workload | Warmup / measured cycles | Actual operation |
| --- | --- | --- |
| `native-factory` | 10 / 100 | EGL/audio module/WebRTC factory allocation and disposal; no capture or peer connection |
| `real-call` | 10 / 100 | Real local HTTPS/WSS/Pion/TURN join/leave using one application-scoped engine, one idle remote peer, capture disabled; extra idle settle 65000 ms |
| `activity-recording` | 3 / 20 | Real recording list/detail/playback, Activity recreation, retained ViewModel check, player/capability close |
| `auth` | 3 / 20 | Actual fixture login, authorized recording data and logout/state clearing; one server profile, not server-switch coverage |

```sh
python3 testing/stage09/copy_fixture_inputs.py --serial "$qualitySerial" --stage 6
python3 testing/stage09/run_lifecycle.py --serial "$qualitySerial" \
  --label baseline --method native-factory --cycles 100
python3 testing/stage09/observe_call_cycles.py --serial "$qualitySerial" --label baseline

python3 testing/stage09/copy_fixture_inputs.py --serial "$qualitySerial" --stage 8
python3 testing/stage09/run_lifecycle.py --serial "$qualitySerial" \
  --label baseline --method activity-recording --cycles 20 --trace
python3 testing/stage09/run_lifecycle.py --serial "$qualitySerial" \
  --label baseline --method auth --cycles 20
```

The default recording comparison above has no additional idle wait. `run_lifecycle.py --idle-settle-ms 65000` can add a separate matched recording experiment, but do not silently mix it into an existing baseline. Every workload takes a warmup snapshot, snapshots every ten measured cycles (and the last cycle), and a settled snapshot after 5000 ms; snapshots request GC/finalization three times. HPROF export adds more GC/stop-the-world overhead. `completedCycles` includes warmup (110 or23), so it is not the measured cycle count.

`observe_call_cycles.py` supplies the 65000 ms additional call settle argument itself and requests explicit Go GC from the owned fixture while sampling its numeric runtime/SFU counters. Its host `lsof` record count includes mappings and is not a count of Android file descriptors. The helper's final observer label110 reflects its fixed 10+100 workload. It does not measure microphone/camera/screen capture and does not prove battery behavior.

`--trace` records the first **45000 ms**, configured in `lifecycle.perfetto.txt`. The trace includes instrumented Activity/player work, GC, heap dumps and software-GPU overhead; it does not cover the entire workload or isolate ordinary user scrolling. A frame deadline miss from this run must not be presented as user-visible production jank. Raw traces and heaps are unsanitized private artifacts.

### Functional checks and summaries

`run_android_tests.py` accepts explicit class/method names and repeated `--arg key=value`. It exports test identity/status/counts while keeping raw instrumentation output private. Check `passed`, `failed` **and `skipped`**; runner success does not make skipped live scenarios pass. For example, after installing normal comparison APKs and copying the matching disposable fixture:

```sh
python3 testing/stage09/copy_fixture_inputs.py --serial "$qualitySerial" --stage 6
python3 testing/stage09/run_android_tests.py --serial "$qualitySerial" \
  --label after-signaling --arg quality09=true \
  --classes com.meetspace.app.CallSignalingLifecycleTest
python3 testing/stage09/copy_fixture_inputs.py --serial "$qualitySerial" --stage 8
python3 testing/stage09/run_android_tests.py --serial "$qualitySerial" \
  --label after-recordings --classes com.meetspace.app.LiveRecordingsTest
```

After the expected baseline and after JSON files exist and have completed states, generate numeric summaries:

```sh
python3 testing/stage09/summarize_measurements.py
```

The summarizer reads existing evidence; it neither runs missing workloads nor converts absent evidence into passing tests. It computes warmup→settled deltas for memory/CPU/allocation/GC/UID bytes and preserves reference counters/extra-idle samples. RSS/PSS growth or a surviving weak reference alone does not establish a leak or a causal performance improvement. UID traffic is process/application accounting under the fixture workload, not a per-endpoint request audit. The current summary's condition text is specific to this audit's API36 ARM64/4KiB setup; another setup needs a separately reviewed report, not that unchanged label.

Perfetto SQL runs only when `.audit/tools/perfetto-v58.2/trace_processor_shell` exists, against the private baseline/after recording traces. Use the audited v58.2 processor SHA-256 `d29864d1ba3b36855527bb1b0ca3aa7f703cdce338b9680bb922c5c151b358fa`; this script records the expected identity but does not download or verify the executable. Independently verify it before use. Its process selector uses the main-thread Linux name `m.meetspace.app`; confirm that mapping in each trace. If the tool/trace is absent, no fresh Perfetto assessment is produced; an older report must not be mistaken for a new result.

### Optional LeakCanary and private outputs

LeakCanary2.14 is a separate debug experiment, excluded from normal baseline/after APKs and release dependencies. Save the normal app/test pair before this build, then install **both** optional APKs on the owned emulator:

```sh
./gradlew --no-daemon -PmeetspaceLeakCanary=true :app:assembleDebug :app:assembleDebugAndroidTest
"$qualityAdb" -s "$qualitySerial" install -r app/build/outputs/apk/debug/app-debug.apk
"$qualityAdb" -s "$qualitySerial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 testing/stage09/copy_fixture_inputs.py --serial "$qualitySerial" --stage 8
python3 testing/stage09/run_lifecycle.py --serial "$qualitySerial" \
  --label leakcanary --method activity-recording --cycles 20 --leakcanary
```

Only the recording workload calls the explicit synchronous `LeakAssertions.assertNoLeaks` hook. `assertion-returned` means this configured detector reported no application leak in that run; it is not proof that every object/native resource is leak-free. Reinstall the preserved normal APK pair, or rebuild without the property and verify its hashes, before further comparable measurements or final artifact verification. Never put this optional run into the normal before/after performance table.

Raw instrumentation logs, HPROFs, traces, private fixture credentials and LeakCanary reports remain under ignored `.audit` or app-private storage; heaps can retain passwords, tokens, URLs and message contents. HPROFs copied by helpers are mode0600; use the private directory/umask above for other raw files. Do not attach or upload them, use broad log dumps in reports, or treat a raw heap as sanitized because the companion JSON is numeric. Export only reviewed evidence. These helpers leave device cache copies in place; after all consumers finish, remove only this audit's captured files/fixture pairs and stop/remove only its owned fixtures/AVD following the fixture cleanup instructions. No shared-device data clearing or global Docker cleanup is part of this procedure.

## GitHub Actions scope

`.github/workflows/android-quality.yml` uses Ubuntu 24.04, JDK17, compile SDK36, Build Tools35.0.0 and an isolated API35 Google APIs x86_64 emulator with KVM and SwiftShader. It records SDK/emulator versions, actual API/ABI/page size and configures 2560×1600, density320, font1.0. The requested SDK package names are fixed, but their repository revisions and hosted runner image can change; the run records installed versions. This is not a reproducible emulator-image binary lock. The GPU mode is `swiftshader`; older `swiftshader_indirect` is [deprecated in current Android Emulator](https://developer.android.com/studio/run/emulator-acceleration).

The job performs:

1. Gradle JVM tests, debug/release lint, debug/test APK, minified unsigned release APK/AAB and release dependency graph.
2. Python installer protocol tests and artifact verifier regressions.
3. Hash-pinned bundletool download and APK/AAB verification.
4. Explicit offline instrumentation classes: FoundationUi, MeetingsUi, CallUi, MessagingUi, RecordingsUi, SettingsUi, InstallerUi, SshWizardUi and NativeLibrariesSmoke tests.
5. APK/AAB, mapping and test/verification report upload for 14 days, including reports on failure. `.audit`, local credentials, Firebase config, signing identities and heap dumps are not uploaded.

The explicit test allowlist excludes private fixture/live tests and opt-in quality stress measurements. This is **not** live login, Pion/TURN calling, VPS installation, object-storage playback, FCM, physical-device, battery or 16 KiB coverage. These scenarios require their documented disposable environments and separate evidence. The workflow fails if a Firebase config or release signing environment is present because it verifies the current unconfigured/unsigned release variant.

Public third-party actions were resolved on 2026-10-11 to full official release commit SHAs:

| Action | Pinned release and commit |
| --- | --- |
| [actions/checkout](https://github.com/actions/checkout/releases/tag/v7.0.1) | v7.0.1 — `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| [actions/setup-java](https://github.com/actions/setup-java/releases/tag/v6.0.1) | v6.0.1 — `de7274f081f381c8f8158605e0321c36c376e2e6` |
| [actions/upload-artifact](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | v7.0.2 — `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |

Their pinned `action.yml` files use Node24; use a current hosted runner (old self-hosted runners are not covered). Checkout does not persist credentials. The workflow has only `contents: read`, uses `pull_request` rather than `pull_request_target`, and has no production secrets or deployment step. See [GitHub action security guidance](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions#using-third-party-actions) and [Android emulator command-line documentation](https://developer.android.com/studio/run/emulator-commandline).

## Verification limits

- Source-level Python/YAML/shell checks do not prove a successful GitHub run. First remote execution must check KVM availability, SDK downloads, resource limits, native loader behavior and Compose test timing.
- Emulator system image and bundletool downloads need network access. Backend fixtures are unnecessary for the selected offline tests; their absence is not counted as a passing live test.
- Release-graph OSV/license evidence is in `docs/android/evidence/09/security-*.json`; CI does not silently report old OSV results as a fresh vulnerability scan. Repeat exact-version queries when the resolved graph changes and before distribution.
- Clean CI has no local `.audit` fixture secret corpus. The artifact scan reports the actual number of available input files and checks fixed test markers; it does not claim a complete secret scan.
- JNI loader smoke is one API/ABI configuration, with actual page size recorded. It does not establish SFU/media functionality or broad device support.
