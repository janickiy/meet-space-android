# Stage08: real recordings consumption fixture

The fixture assembles unchanged backend commit `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69` with its actual migrations, Gin handlers, durable auth, PostgreSQL repositories, Redis and private MinIO. Recordings/content use the backend's real authorization and signing code. This fixture verifies **consumption of existing recordings**. It does not exercise live capture, media workers, transcription or AI generation.

Run from the Android workspace:

```sh
python3 testing/stage08/fixture.py start --backend-source .audit/meet-space-stage04
python3 testing/stage08/run_contract.py
MEETSPACE_STAGE08_LIVE=1 \
MEETSPACE_STAGE08_FIXTURE="$PWD/.audit/stage08-fixture/live.properties" \
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" \
./gradlew :core:data:testDebugUnitTest --tests '*LiveRecordingsBackendTest'
```

Requirements: Python 3, Git, OpenSSL, Go/toolchain from the pinned backend, Homebrew PostgreSQL tools, Docker, `redis:7-alpine`, a real MinIO image (`go-recorder-minio:local` default), and an installed FFmpeg worker image (`go-recorder-worker:latest` default). `--minio-image` and `--ffmpeg-image` accept alternatives. No software is installed or external account configured. FFmpeg runs in a new labeled disposable container with `--network none`; existing containers are untouched.

FFmpeg creates a decodable ten-second H.264 baseline/AAC MP4, an AAC M4A, and JPEG preview from color bars and a sine wave. The contract command creates real users' meetings/memberships/recording requests through REST, uploads those bytes to private MinIO, and transitions the recording with the real repository's `MarkFinalizing`, `MarkUploading`, and `SaveFinalArtifacts` methods. It explicitly seeds 205 historical transcript segments and a summary with `provider=fixture_seed`. Both external providers remain `noop`; there is no simulated recognition/AI response or claim of a successful capture pipeline. A separate `debug_log` artifact exercises the Android file allowlist.

The private home `.audit/stage08-fixture` is mode 0700; credential and key files are mode 0600. Never publish it. Ports are PostgreSQL15488, Redis16388, API18488, MinIO19088, storage HTTPS19488. Only the private storage TLS proxy binds the host interface for JVM/emulator signed-host compatibility; data services bind loopback. Certificates include `127.0.0.1`, `10.0.2.2`, `localhost`, and the host LAN IP. Consumers inject only this fixture CA and retain standard hostname verification.

`live.properties` contains aliases and four disposable account pairs `email0/password0` through `email3/password3`. Account0 owns the finished main meeting, account1 is an admitted former participant, account2 has no membership, account3 was kicked through the real moderation endpoint. After a successful contract run it also contains `conferenceId`, `otherConferenceId`, `recordingId`, `secondRecordingId`, `audioRecordingId`, `videoFileId`, `previewFileId`, `audioFileId`, `debugFileId`, `transcriptId`, `summaryId`, `segmentCount`, `mediaSha256`, and `mediaBytes`. Repeated contract runs create fresh IDs/history; restart for deterministic empty-state counts.

## Android instrumentation

The test APK alone contains `LiveRecordingsBackend`, which constructs the actual repository with RAM profile/credential stores and the private CA. `LiveCallApplication` selects it only when the private `live-recordings.properties` marker exists, ahead of older fixture markers. No production application API or trust manager is changed.

After installing current debug/test APKs, stop other instrumentation, then copy inputs through stdin without passwords in command arguments:

```sh
python3 - <<'PY'
from pathlib import Path
import subprocess
adb = str(Path.home()/'Library/Android/sdk/platform-tools/adb')
fixture_root = Path('.audit/stage08-fixture')
subprocess.run([adb, 'shell', 'am', 'force-stop', 'com.meetspace.app'], check=True)
properties = (fixture_root/'live.properties').read_text().replace('origin=https://127.0.0.1:18488', 'origin=https://10.0.2.2:18488').encode()
for name, content in [('live-recordings.properties', properties), ('live-recordings-ca.pem', (fixture_root/'tls/ca.pem').read_bytes())]:
    subprocess.run([adb, 'exec-in', 'run-as', 'com.meetspace.app', 'dd', 'of=files/'+name, 'bs='+str(len(content)), 'count=1'], input=content, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([adb, 'shell', 'run-as', 'com.meetspace.app', 'chmod', '600', 'files/'+name], check=True)
PY
```

Run `com.meetspace.app.LiveRecordingsTest` with runner `com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner`. Its methods verify:

- `realVideoPlaysSeeksRotatesAndReleases`: actual login, Calls→recordings→meeting→MP4, Media3 readiness plus decoded/rendered frame counter, seek, explicit user pause and retained position through rotation, and capability release.
- `realHistoricalMaterialsPaginateAndSettingsReflectActualState`: actual ready historical materials despite disabled providers, all 205 unique segments, summary, diagnostic artifact exclusion, real theme preferences and privacy dialog.
- `actualSystemCreateDocumentDownloadsVerifiedMp4AndCleansItsFile`: real DocumentsUI, newly-created UUID filename, production streaming export, source SHA256, removal of only that exact test-created export. Shell hash is a test observer; the app uses the returned SAF URI and requests no broad storage permission.

- `realExistingServerAndUnavailableSshOnboarding`: actual Welcome→HTTPS connection check→UI login→UI logout; reset only the fixture RAM profiles; Welcome→SSH host-key probe on the device-local closed `127.0.0.1:1` endpoint and actionable real transport failure. No SSH secret, external VPS or installation is involved. Private login interactions suppress diagnostic dumps and have no screenshots. A complete new-VPS install remains unverified without a disposable authorized host/domain.

`NativeLibrariesSmokeTest#bundledNativeLibrariesLoadAndWebRtcFactoryDisposes` is independent of the fixture. It loads `androidx.graphics.path`, `datastore_shared_counter`, `jingle_peerconnection_so`, creates/disposes a WebRTC factory without peers/tracks/capture, and writes `cache/stage08-native-runtime.json` with the actual kernel page size. On the 16KiB image run it with instrumentation argument `-e expectedPageSize 16384`; a different page size fails explicitly. This smoke does not establish a working call.

Tests write only reviewed fixture screenshots and count/boolean evidence under app-private cache `stage08-live-*`, plus safe progress `stage08-progress.txt`. Do not infer a passing runtime check from compiled sources. Root test execution and results belong in `docs/android/08_TESTING.md`.

## Cleanup

After all consumers finish, force-stop the app and remove its private test inputs:

```sh
adb shell am force-stop com.meetspace.app
adb shell run-as com.meetspace.app rm -f files/live-recordings.properties files/live-recordings-ca.pem
python3 testing/stage08/fixture.py stop --remove
```

Stop validates the home marker, process command path, exact recorded container IDs and stage08 ownership label. It never stops unrelated containers or edits the source backend checkout. Removal destroys disposable credentials, private keys, DB/media and private logs. Copy only sanitized result JSON and reviewed evidence first. Run Gradle tasks sequentially because modules share result directories.

## APK/AAB verification

After the final APK/AAB build, run:

```sh
python3 testing/stage08/verify_artifacts.py --generate-smoke-apk
```

The verifier writes sanitized `docs/android/evidence/08/artifact-verification.json`, leaves unsigned release outputs unchanged, and can generate a private smoke APK signed with a temporary key that it deletes. It does not install to a device. It uses pinned bundletool1.18.3 at `.audit/tools/bundletool/bundletool-all-1.18.3.jar`; SHA256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`, downloaded from the [official release](https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar).

## Strict 16 KiB runtime verification

Use a separate, disposable Android 16 KiB ARM64 emulator. The stage08 image was Google's API36 revision7 `arm64-v8a-ps16k-36_r07.zip`, whose SDK repository SHA1 is `dda632745023567113d9954101709c6526a3c250`. It was verified before extraction; the host's existing tablet AVD was not overwritten. With more than one device attached, pass the intended `-s SERIAL` to every adb command, including private fixture input transfer above.

Before installing the APK, disable both compatibility paths and verify the actual values:

```sh
adb -s emulator-5556 shell setprop bionic.linker.16kb.app_compat.enabled false
adb -s emulator-5556 shell setprop pm.16kb.app_compat.disabled true
adb -s emulator-5556 shell getconf PAGE_SIZE
adb -s emulator-5556 shell getprop bionic.linker.16kb.app_compat.enabled
adb -s emulator-5556 shell getprop pm.16kb.app_compat.disabled
```

Expected results are `16384`, `false`, and `true`. Install current debug and test APKs, inject the fixture inputs, then run:

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e expectedPageSize 16384 \
  -e class com.meetspace.app.LiveRecordingsTest,com.meetspace.app.NativeLibrariesSmokeTest \
  com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
```

For release launch verification, use only the disposable emulator: uninstall its debug app (the temporary release certificate differs), install `.audit/stage08-release-smoke/app-release-smoke.apk`, and launch the ordinary application without instrumentation or fixture CA. Do not uninstall a user's existing installation to work around a signature mismatch. A launch smoke does not establish release authentication or playback with the private CA.

Record actual device properties, test results and SHA-256 values in the shape of `docs/android/evidence/08/runtime-16k.json`. Then bind that evidence to the current packages:

```sh
python3 testing/stage08/verify_artifacts.py \
  --runtime-report docs/android/evidence/08/runtime-16k.json
```

This mode checks device/page/compatibility fields, package/version, all artifact hashes, smoke APK signature, and equality of executable DEX/native libraries/assets with the current AAB. It accepts recorded observations; it does not run Android tests itself. Do not combine it with smoke regeneration, which would invalidate the recorded APK hash. ARM64 results do not establish x86_64 or physical-device compatibility.
