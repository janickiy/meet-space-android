# Disposable beta 0.1.0 registration and direct-call integration

This assembly runs current Go backend handlers/repositories with private PostgreSQL,
Redis, MinIO and Pion SFU/TURN. The original backend checkout is read-only. Its current
working Go source and migrations are copied into the private fixture home; source hashes
and base commit are recorded. Only the additional assembly command is test code.

The fixture is strictly separate from production. API and database/container TCP ports
bind loopback. SFU/TURN UDP uses the host LAN address for real emulator relay transport.
All generated credentials, private CA/keys, databases and logs stay in the marked 0700
`.audit/beta010-fixture`; they must never be packaged with the app or published.

```sh
python3 testing/beta010/fixture.py start
python3 testing/beta010/run_contract.py
MEETSPACE_BETA010_LIVE=1 \
MEETSPACE_BETA010_FIXTURE="$PWD/.audit/beta010-fixture" \
./gradlew :core:data:testDebugUnitTest --tests '*LiveRegistrationDirectCallsTest'
```

Run Gradle tasks sequentially. The live repository tests instantiate the unchanged
production `DefaultFoundationRepository`, `MeetSpaceApi`, cookie isolation and direct-call
adapter with RAM test stores and trust only this private CA. Hostname verification remains
enabled. They verify real registration/session/restore/revoke, real incoming UserWS,
acceptance and endpoint authorization, conference WSS, relay configuration and stored call
history. Native media transfer is verified separately by the Android direct-call test.

Ports: PostgreSQL15410, Redis16310, API HTTPS18410, media control18910,
SFU UDP50110, TURN UDP13410, MinIO19010. MinIO is private and supplied only to real chat
service initialization/history; attachment upload/export is outside these tests.

Android inputs are `beta-direct-calls.properties` and `tls/ca.pem`, copied privately to
app `files/beta-direct-calls.properties` and `files/beta-direct-calls-ca.pem`. The prepared
properties use `https://10.0.2.2:18410`, with separate disposable caller/peer credentials
and IDs. Do not install the fixture CA into system trust or change production TLS settings.

The REST runner and live tests create fresh actors, keeping the prepared Android accounts
free of active call slots. Sanitized REST evidence contains check names/statuses only.
Do not copy private fixture logs without reviewing them for secrets.

After all JVM and Android consumers finish, clean up only the fixture's own marked home:

```sh
python3 testing/beta010/fixture.py stop --remove
```

Cleanup verifies PID executable/settings paths and Docker fixture labels before stopping
recorded resources. It never stops unrelated containers or edits the source checkout.
