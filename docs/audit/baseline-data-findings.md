# Data, SSH and installer baseline findings — 2026-10-27

This file records the observed state before remediation in this audit. No remote
installer, backend or user-server operations were performed for this review.

## DATA-01 — Whole-backup allocations during installer backup verification (P2)

Evidence before the fix: `installer/v1/runner.py`, `Worker.phase_backup`, lines
1802, 1819 and 1821 read an entire PostgreSQL dump with `Path.read_bytes()` to
hash a reused dump, feed `pg_restore --list`, and hash the new backup receipt.
There is no backup-size bound. Consequently temporary Python heap use scales
with the complete database dump rather than the streaming buffer size. A large
database can exhaust RAM on a supported 4 GB VPS and leave installation awaiting
recovery. This is a confirmed unbounded allocation; a production OOM was not
reproduced and is not claimed.

Local controlled evidence: hashing a 32 MiB sparse synthetic dump using the
pre-fix `hashlib.sha256(path.read_bytes())` expression produced a traced Python
heap peak recorded in `.audit/audit-backup-memory-before-20261027.json`. This
measures the hash expression only, not a full remote installation.

The runner already has a chunked `file_digest()` helper. The intended remediation
is to use it for both checksums and stream the backup file into `pg_restore` via
the subprocess stdin file descriptor. Keep the existing partial-file cleanup,
atomic publication, receipt validation and error codes. Regression checks should
cover a new successful backup, reuse, invalid restore/dump failures and bounded
memory with a synthetic large local file. The signed bundle must be re-signed
by the parent after the runner is finalized.

## Inspected safeguards and executed baseline checks

- `core:data`: exact HTTPS origin routing, system TLS/hostname checks, redirects
  disabled, no HTTP cache or body/credential logging, bounded JSON/file reads.
  HttpOnly refresh cookies are exact-origin/auth-path scoped. Persistent sessions
  use AES-256-GCM with Android Keystore, scope AAD and no-backup private files.
  Logout/server/account change closes WebSockets and recording leases; at most
  eight recording leases are retained. REST and auth mutations share a mutex.
- `core:ssh`: JSch 2.28.8, Bouncy Castle 1.86, explicit host/port public-key pins,
  strict host checking/key exchange, inspection aborts before authentication,
  fixed shell commands with validated quoted inputs, stdin-only installer data,
  two blocking worker threads, cancellation closes TCP/session/channel, output
  and operation deadlines, credential byte-array wiping in `finally`.
- Installer: signed RSA/SHA-256 recipe, frozen source/image metadata, explicit
  apply consent, root-owned path/symlink checks, job locking, durable recovery
  journal, private configuration and preserved managed volumes.
- Executed locally with no installation or external mutations:
  `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s installer/tests -p 'test_*.py'`.
  Result: **126 tests passed**, 0 failures, 0 skips (0.433 s).
  Log: `.audit/audit-data-installer-tests-20261027.log`.
- At baseline collection, no Gradle, live IDOR, real SSH installer or physical-
  device profiling had been run by this subtask. No confirmed P0/P1 finding in
  the inspected area at that stage.

## DATA-01 remediation and controlled verification

The runner now hashes both new/reused backup files through the existing chunked
`file_digest()`. `pg_restore --list` receives an open backup file as subprocess
stdin; the generic `run()` and `Worker.compose()` adapters propagate that file
descriptor and reject simultaneous byte input plus a stdin file. Verified or
failed partial-file cleanup, atomic publication and receipt fields stay intact.

Eight focused regressions passed:
`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s installer/tests -p test_backup_streaming.py -v`.
They cover success, reuse, checksum corruption, dump failure, restore failure,
closed stdin descriptors, an actual local subprocess consuming file stdin,
ambiguous input rejection and 8/64 MiB synthetic dumps with traced heap below
4 MiB. Complete backup `read_bytes()` calls are explicitly prohibited by the
fixture while small JSON receipt reads remain supported.

Controlled memory evidence for a 32 MiB synthetic dump:

| Workload | Traced Python heap peak |
|---|---:|
| Pre-fix hash expression `sha256(path.read_bytes())` | 33,559,058 B |
| Fixed complete backup phase, mocked Docker dump/restore | 2,136,534 B |

The two measurements cover different amounts of work: the old row measures only
the offending hash expression, whereas the new row covers the entire backup
phase. They demonstrate bounded buffer use but are not VPS RSS, a real PostgreSQL
restore drill, or production latency/battery measurements. Evidence:
`.audit/audit-backup-memory-before-20261027.json`,
`.audit/audit-backup-memory-after-20261027.json`, and
`.audit/audit-backup-streaming-tests-20261027.log`.

The parent must re-sign `release.json`/`release.sig` after reviewing the final
runner. This subtask did not modify the trust root, signature or release manifest.

## Final local regression and contract checks

After the parent re-signed the bundle, the complete installer command above
passed **134 tests**, 0 failures, 0 skips (0.439 s). Log:
`.audit/android-audit-20261027/installer-data-final.log`.

A new isolated fixture was started with:

```sh
GOTOOLCHAIN=go1.26.10 PYTHONDONTWRITEBYTECODE=1 python3 testing/stage05/fixture.py start \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture05 \
  --backend-source /Users/aleksandranickij/htdocs/go-recorder \
  --postgres-bin /opt/homebrew/opt/postgresql@14/bin --pg-port 15465 --https-port 18465
MEETSPACE_STAGE05_HOME=/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture05 \
  PYTHONDONTWRITEBYTECODE=1 python3 testing/stage05/backend_contract_check.py
```

Result: **60 checks passed** against real Gin/PostgreSQL 14 with a validated
fixture HTTPS certificate. The harness pins backend commit
`6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`; this is the harness contract baseline,
not a claim that the newest production backend or SFU/media stack was tested.
Checks include auth, membership/owner boundaries, meeting lifecycle, timeline
cursor/date validation, history, profile validation and revoked sessions. There
were no production mutations or business-response mocks. Sanitized evidence was
copied outside the private fixture for retention:
`.audit/android-audit-20261027/fixture05-contract-result.json`.

The fixture home is mode 700, test credential file mode 600. It remains running
for the parent's live JVM checks; no secret values were printed. Parent cleanup:

```sh
python3 testing/stage05/fixture.py stop \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture05 --remove
```

No Gradle or real SSH installer invocation was performed by this subtask. All
remaining device-memory, battery, network-handoff and physical media claims need
the parent's separate evidence; this review does not infer them from passing
unit/contract checks.

## Additional messaging/files/WebSocket contract fixture

An independent stage07 fixture was started in
`.audit/android-audit-20261027/fixture07`, with backend pin
`6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`, isolated PostgreSQL 14, Redis 7, real
MinIO and actual Gin HTTPS/WSS handlers. Existing containers/services were not
modified. The fixture owns only its marker/PID/datadir and two recorded Docker
containers labelled `meetspace.fixture=meetspace-disposable-stage07`.

```sh
GOTOOLCHAIN=go1.26.10 PYTHONDONTWRITEBYTECODE=1 python3 testing/stage07/fixture.py start \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture07 \
  --backend-source /Users/aleksandranickij/htdocs/go-recorder \
  --postgres-bin /opt/homebrew/opt/postgresql@14/bin
GOTOOLCHAIN=go1.26.10 PYTHONDONTWRITEBYTECODE=1 python3 testing/stage07/run_contract.py \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture07
```

The runner completed **460 checks**, including two final evidence/property-write
checks after serializing its report. The sanitized report contains **458 passed
checks** and is retained at
`.audit/android-audit-20261027/fixture07-contract-result.json`; terminal evidence
is `.audit/android-audit-20261027/fixture07-contract.log`. It uses no business
mocks and reports no production mutations. It exercises real user WebSockets,
messages/groups/folders, private attachment upload/download and authorization
boundaries. It does not establish Firebase delivery, media/SFU, physical-device
audio or comprehensive live production authorization coverage.

Ports are API 18477, PostgreSQL 15477, Redis 16377, MinIO 19077 and signed-storage
TLS 19477. The private home is 700 and `live.properties` is 600. Parent live JVM
inputs are `MEETSPACE_STAGE07_LIVE=1` and `MEETSPACE_STAGE07_FIXTURE` pointing to
that private file. The fixture remains running for the parent's JVM/Android
checks; remove it only after those consumers finish:

```sh
python3 testing/stage07/fixture.py stop \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture07 --remove
```

## Disposable SFU/TURN fixture prepared for parent native checks

An independent stage06 home was prepared at
`.audit/android-audit-20261027/fixture06`, using the same harness backend pin.
TCP 15466/18466/16366/18966 and UDP 13466/50166 were verified available before
starting. The harness provides actual Gin HTTPS/WSS, PostgreSQL, a separate Redis
container, production media controller/Pion SFU and authenticated Pion TURN.

```sh
GOTOOLCHAIN=go1.26.10 PYTHONDONTWRITEBYTECODE=1 python3 testing/stage06/fixture.py start \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture06 \
  --backend-source /Users/aleksandranickij/htdocs/go-recorder \
  --postgres-bin /opt/homebrew/opt/postgresql@14/bin
GOTOOLCHAIN=go1.26.10 PYTHONDONTWRITEBYTECODE=1 python3 testing/stage06/run_peers.py \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture06 --count 1
```

The peer reached `connected`; RTP counters were zero before Android joined.
Thus this is real host-side negotiation readiness, not proof that Android media
or the lifecycle regression has passed. One peer sets `expectedPeers=2` for the
parent's Android test, keeping resource use lower than the five-person fixture.
Both launchers and all consumers use the explicit custom home, so no default
fixture state is mixed in.

One idle resource sample (no forced GC): fixture process RSS 212,064 KiB, Pion
peer 20,128 KiB, PostgreSQL master/children total 76,320 KiB; summed RSS is 308,512
KiB and double-counts shared pages. Separate Redis container memory was 10.5 MiB
with six PIDs. Go runtime reported heap 76,173,312 B and 50 goroutines after
Argon2 registration warm-up. This single sample is not a stabilized leak profile
or Android memory measurement. Sanitized evidence:
`.audit/android-audit-20261027/fixture06-resource-readiness.json`.

Parent native regression: `CallSignalingLifecycleTest#rejectedIceClosesAlreadyOpenedSignaling`
with `quality09=true`, using app-private `live-call.properties` (origin rewritten
only to `https://10.0.2.2:18466`) and `live-call-ca.pem`. Remove other fixture input
markers before changing test application selection. The existing
`LiveCallTest#nativeCameraRelayReconnectAndCleanup` additionally requires emulated
cameras; no-audio still allows a silent Opus RTP check but cannot establish human
audibility. No ADB, native instrumentation or Gradle was run by this subtask.

The fixture and peer remain available for the parent's tests. Afterwards:

```sh
python3 testing/stage06/fixture.py stop \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture06 --remove
```

## APP-006 — optional chat resource failure invalidates an accessible conversation (P1)

Recorded before the production change. The actual stage07 Android run reproduced
conversation detail/history loading successfully, followed by selection/history
being cleared and an access error. The pinned stage07 backend
`6ecd8b648a2aa94fc9bcc844d57784423e2c6f69` has no
`/conversations/:id/pins` route; the current backend registers that later API.
`MessagingViewModel.open` unconditionally loads pins. Before the fix its pins
failure handler called `accessDenied` for `NOT_FOUND`, and that helper immediately
called `back`, dropped loaded history/drafts, and removed the conversation from
the list. A missing optional route was therefore treated as revoked chat access.

The same code path exists for optional search/context/pin mutations. File download
or upload-finalization `NOT_FOUND` can also identify a missing attachment rather
than a revoked conversation. These resource-specific failures need canonical
conversation authorization revalidation before clearing chat history. Canonical
conversation or main history `FORBIDDEN`/`NOT_FOUND` must continue clearing access.

Evidence: `.audit/android-audit-20261027/after-live-messaging.log` and
`final-live-messaging.log` (private local logs); the added diagnostics contain only
boolean state/UI-shape facts. No production data or API was changed to reproduce
this finding. The parent authorized a focused Android fix and regressions for
canonical success/revocation and transient failures, preserving actual live
HTTPS/WSS/SAF assertions.

Remediation: optional pins/search/context requests and pin mutations retain the
conversation until canonical authorization is checked. A denied optional
projection is cleared and its error remains visible; context failure reloads
the ordinary history. Attachment upload/download failures follow the same
canonical revalidation rule. Direct conversation/history denials retain their
existing immediate cleanup behavior. Temporary network failures keep loaded
history/projections and do not trigger access revocation or destructive retry.

Four regression methods cover initial pins404 on an older backend, six resource
types with optional403/404 and canonical200, canonical403/404 cleanup, and
transient cache retention. The parent's Gradle run produced
`app/build/test-results/testDebugUnitTest/TEST-com.meetspace.app.MessagingViewModelTest.xml`
at `2026-10-26T23:57:35.473Z`: **48 tests, 0 failures/errors/skips** including
these new cases. Actual Android HTTPS/WSS/SAF reruns remain the parent's work;
JVM success alone does not establish that native integration has passed.

Финальная severity APP-006: P2 (доступная история/черновик ошибочно сбрасывались при отказе дополнительного endpoint). Первичная P1-оценка выше сохранена как историческая; серверная авторизация не обходилась.
