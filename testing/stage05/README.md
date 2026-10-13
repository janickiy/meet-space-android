# Isolated real backend checks for Android stage 05

This fixture binds loopback HTTPS and a separate PostgreSQL process. It clones pinned backend `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`, applies its 33 actual migrations, and registers its real Gin auth/conference routes, Argon2/JWT durable session middleware, PostgreSQL repositories, version and capability handlers. It creates two disposable accounts. No business response is mocked. Redis, SFU and media workers are outside these checks.

Prerequisites: Python 3, Git, OpenSSL, Go with automatic toolchain download enabled (backend requires 1.26.9), and PostgreSQL `initdb`, `pg_ctl`, `createdb`. `fixture.py` discovers a Homebrew PostgreSQL installation; otherwise pass `--postgres-bin /path/to/postgresql/bin`. It does not install software, change running services, or use production.

From the Android repository root:

```sh
python3 testing/stage05/fixture.py start
python3 testing/stage05/backend_contract_check.py
MEETSPACE_STAGE05_LIVE=1 MEETSPACE_STAGE05_FIXTURE="$PWD/.audit/stage05-fixture/live.properties" ./gradlew :core:data:testDebugUnitTest --tests com.meetspace.core.data.LiveMeetingsRepositoryTest
python3 testing/stage05/fixture.py stop --remove
```

Start requires a new private home, default `.audit/stage05-fixture`, and unused ports 15455/18455. Existing homes are never overwritten. Build evidence from the completed stage remains in the separate `.audit/meetings-stage05` directory. To reuse an already fetched clean repository, add `--backend-source .audit/meet-space-stage04`; Git clone ignores its untracked fixture and local modifications. For separate ports/home:

```sh
python3 testing/stage05/fixture.py start --home .audit/ms05-repeat --pg-port 15456 --https-port 18456
MEETSPACE_STAGE05_HOME="$PWD/.audit/ms05-repeat" python3 testing/stage05/backend_contract_check.py
MEETSPACE_STAGE05_LIVE=1 MEETSPACE_STAGE05_FIXTURE="$PWD/.audit/ms05-repeat/live.properties" ./gradlew :core:data:testDebugUnitTest --tests com.meetspace.core.data.LiveMeetingsRepositoryTest
python3 testing/stage05/fixture.py stop --home .audit/ms05-repeat --remove
```

Tests must finish before cleanup. Finish Gradle invocations before starting another Gradle build in the same workspace.

Only the fixture home contains `settings.json`, test credentials, JWT secret and TLS private keys; the home is mode 700 and credentials/keys mode 600. Do not commit or print these files. `live.properties` contains origin, email/password, secondEmail/secondPassword and caPath. Certificate trust is injected only into the test client using its fixture CA and standard hostname verification. No system or Android CA store is changed, and no insecure TLS mode is used. PostgreSQL trust auth is confined to the disposable loopback process; its database contains only fixture data.

`backend_contract_check.py` writes a sanitized `backend-contract-result.json` with check names, HTTP status and pass counts. It covers actual create/schedule/start/join/finish/cancel/history/profile, membership/owner permissions, lifecycle conflicts, invite privacy, date/cursor bounds and revoked sessions. It prints neither response bodies nor account/meeting IDs. Copy only this sanitized evidence into documentation before cleanup. Start logs and process files remain private; failure cleanup stops only the marked fixture's own process and datadir.
