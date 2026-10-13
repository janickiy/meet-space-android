# Stage07: real messaging fixture

The fixture assembles the actual backend at commit `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. Business handlers, PostgreSQL repositories, Redis user WebSocket/presence, notification dispatcher and private MinIO storage are unchanged. Test-only code supplies isolated dependencies and TLS termination. No fake response bodies or production calls.

Requirements on this Mac: Go/toolchain from backend `go.mod`, PostgreSQL `initdb`/`pg_ctl`/`createdb` (Homebrew 14 supported), OpenSSL, Docker, real MinIO image `go-recorder-minio:local` and `redis:7-alpine`. An alternative **real MinIO server** image can be supplied with `--minio-image`. The host address defaults to `ipconfig getifaddr en0`; `--lan-address` overrides it. The emulator and host must reach that address for signed S3 downloads.

```sh
python3 testing/stage07/fixture.py start --backend-source .audit/meet-space-stage04
python3 testing/stage07/run_contract.py
MEETSPACE_STAGE07_LIVE=1 \
MEETSPACE_STAGE07_FIXTURE="$PWD/.audit/stage07-fixture/live.properties" \
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" \
./gradlew :core:data:testDebugUnitTest --tests '*LiveMessagingRepositoryTest'
```

Run Gradle test tasks sequentially: concurrent runs share and overwrite `core/data/build/test-results/testDebugUnitTest`.

Private fixture home mode is 0700. It contains four disposable accounts (`email0`/`password0` … `email3`/`password3`), aliases `email`/`password` and `secondEmail`/`secondPassword`, private CA and keys, database, storage credentials and process/container ownership records. Never copy these to assets, screenshots, reports or source control. The contract runner appends fixture resource IDs, without exporting credentials. Re-running it keeps old disposable history; for deterministic counts start a new fixture after all consumers finish.

Ports: PostgreSQL 15477, Redis 16377, internal MinIO 19077, API HTTPS 18477, storage HTTPS 19477. PostgreSQL/Redis/MinIO listen on host loopback only. The signed storage TLS proxy listens on the host interface so both JVM and emulator can use its original signed host. It preserves MinIO signature validation and never synthesizes objects. Stop it after testing. The private bucket denies unsigned access.

The API origin in `live.properties` uses `127.0.0.1`; Android instrumentation substitutes `10.0.2.2`. Both names and the storage host IP are present in the certificate SAN. Tests inject only this private CA, keep standard hostname verification and never set an insecure trust manager. Production trust/configuration stays unchanged. Device registration is a real backend route with `push=noop`; it correctly returns 503. No Firebase or gateway delivery is simulated.

Sanitized contract output is `backend-contract-result.json` inside the private home. It contains check names/statuses only. Native JVM evidence can be copied from its JUnit XML; do not publish fixture logs blindly. `snapshot.json` reports only the number of live global user WebSockets.

Cleanup only after Android/JVM consumers finish:

```sh
python3 testing/stage07/fixture.py stop --remove
```

Cleanup verifies the private home marker, the exact executable path before signaling a PID, and the `meetspace.fixture=meetspace-disposable-stage07` label before stopping the recorded container IDs. It never stops existing `go-recorder` containers or edits that checkout. Removing the home destroys disposable passwords, TLS private keys, database and private fixture logs; MinIO/Redis containers use ephemeral storage.

## Android instrumentation with the real application repository

`LiveMessagingBackend.kt` and `LiveMessagingTest.kt` live in `app/src/androidTest` only. The existing `MeetSpaceTestRunner` selects `LiveCallApplication`; when `files/live-messaging.properties` exists it overrides only `repository`. A test-only reflective constructor bridge creates the unchanged internal `DefaultFoundationRepository` and `MeetSpaceApi`, with RAM profile/credential stores and the private fixture CA. It does not widen the production TLS API, replace REST/business methods, write real app profiles or use production accounts. Normal tests without the private marker use the normal app repository. A non-minified debug app is required for this bridge.

Install current debug and test APKs first. Stop other instrumentation before changing private inputs. Copy via stdin so passwords never appear in shell arguments or logs:

```sh
python3 - <<'PY'
from pathlib import Path
import shlex, subprocess
adb = str(Path.home()/'Library/Android/sdk/platform-tools/adb')
home = Path('.audit/stage07-fixture')
subprocess.run([adb, 'shell', 'am', 'force-stop', 'com.meetspace.app'], check=True)
properties = (home/'live.properties').read_text().replace('origin=https://127.0.0.1:18477', 'origin=https://10.0.2.2:18477').encode()
for name, content in [('live-messaging.properties', properties), ('live-messaging-ca.pem', (home/'tls/ca.pem').read_bytes())]:
    command = 'run-as com.meetspace.app sh -c ' + shlex.quote('cat > files/'+name)
    subprocess.run([adb, 'shell', command], input=content, check=True, stdout=subprocess.DEVNULL)
PY
```

Do not leave the stage06 `live-call.properties` marker in the target app while running stage07. Run through `com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner`; exact class/method names:

- `com.meetspace.app.LiveMessagingTest#realDirectMessagesReplyEditDeleteAndRotation`: actual UI composer sends/replies/edits; ViewModel deletion, server confirmation from a second real repository, retained ViewModel/draft across recreation/rotation.
- `com.meetspace.app.LiveMessagingTest#realGroupFoldersMinioFilesAndNotificationCenter`: actual ViewModel group/member/folder operations, UI attachment-message send, real upload/finalize/authenticated MinIO download and byte hash, mixed meeting/chat folder, notification dialog and read acknowledgment.
- `com.meetspace.app.LiveMessagingTest#realOfflineHistoryReconnectAndLogoutCleanup`: temporarily disables the emulator Wi-Fi/mobile data and restores prior enabled flags in `finally`; preserves RAM history on failed refresh; reconnects WSS, resynchronizes a message sent by another real repository while the stream was stopped, clears drafts/history/attachments on logout.

The attachment test supplies controlled bytes to the production ViewModel `upload`/`export` methods; **system document picker/save UI is a separate check**. No picker success is simulated. The tests write only fixture screenshots and boolean/count evidence under app-private cache (`stage07-live-*.png`, `stage07-live-evidence.txt`, `stage07-progress.txt`). Copy reviewed evidence before cleanup. Creating the sources/compiling them does not by itself confirm these instrumentation methods passed.

After all checks, force-stop the app and remove only its test inputs/evidence cache:

```sh
adb shell run-as com.meetspace.app rm -f files/live-messaging.properties files/live-messaging-ca.pem
```

Then stop/remove the host fixture as described above. Neither CA nor credentials belongs in the release APK.
