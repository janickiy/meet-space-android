# Disposable native media interoperability fixture

See [06_WEBRTC_TESTING.md](../../docs/android/06_WEBRTC_TESTING.md).

- `fixture.py` / `main.go`: real HTTPS/Gin/auth/PostgreSQL/Redis/media controller/Pion SFU/TURN assembly, pinned backend checkout.
- `run_peers.py` / `peers.go`: 1–4 real Pion endpoints; echo of Android Opus/VP8 with continuous RTP sequence/timestamps and PLI forwarding.
- All credentials and private keys live under a marked ignored `.audit/stage06-fixture` directory, never in this directory or the app APK.
- Do not run against production or reuse personal accounts. Never disable certificate validation.
