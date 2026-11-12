# Remote speaking regression fixture

This opt-in test passes synthetic PCM from one production Android `CallEngine` through the actual backend Pion SFU to a second production `CallEngine`, then inspects the listener's microphone and tile pixels. It uses an emulator and a disposable local HTTPS endpoint. No microphone recordings, personal accounts, production server, Redis or database are used.

The fixture archives a chosen committed backend revision into its own ignored directory and imports `internal/infrastructure/sfu`. Media negotiation, RTP subscriptions, forwarding and catalog events are real. The small conference roster and HTTP-to-signaling bridge replace only authentication/storage for this test. The `presence-mode` control deliberately returns 503 without persisting presence to exercise stale roster metadata. The `media-state` control injects explicit microphone mute/block events while the previous SFU publication continues sending RTP, to test that statistics cannot override a confirmed mute.

The three Android scenarios cover normal playback, zero speaker volume and failed presence persistence. Each checks actual microphone/border pixels, quiet/loud audio, silence, portrait/landscape resizing, gallery/speaker views, explicit mute before publication removal, microphone recreation and reconnect. A separate receiver APM prevents the two synthetic endpoints in one process from feeding received audio into the sender's echo canceller. Only the synthetic sender's audio-focus-loss callback is disabled so the listening endpoint cannot mute the simulated remote device. Production reception, track binding, PCM meters and UI remain in use.

Start from the Android project root with a local backend checkout:

```sh
python3 testing/speaking-sfu/fixture.py start \
  --backend-source /path/to/meet-space \
  --revision HEAD
```

The resolved commit is stored in `.audit/speaking-sfu/backend-commit.txt`. Ports 18477/TCP and 50377/UDP must be free. The HTTPS endpoint binds to localhost, and the SFU advertises the Android emulator's host gateway `10.0.2.2`. The test does not support physical devices or remote servers.

For the disposable emulator only, supply these files in `com.meetspace.app`'s app files directory through `adb shell run-as` or `adb exec-in run-as`:

- `sfu-speaking.properties`, containing `origin=https://10.0.2.2:18477` and a final newline.
- `sfu-speaking-ca.pem`, copied from `.audit/speaking-sfu/tls/ca.pem`.

Build/install the debug and androidTest APKs, then run `com.meetspace.app.RemoteSpeakingSfuTest` using the project's `MeetSpaceTestRunner`. Without those input files, or on a physical device, the test is skipped. TLS verification remains enabled and trusts only the disposable fixture CA.

Stop the fixture after testing:

```sh
python3 testing/speaking-sfu/fixture.py stop
```

The script verifies its marker and the exact running binary path before terminating its process. It preserves artifacts for diagnosis. Choose a new `--home` for a subsequent run. Private TLS keys must never be committed or copied into the application APK.
