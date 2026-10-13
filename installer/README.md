# MeetSpace remote installer v1

Android delivers the five public files in `v1/` over an exactly pinned SSH connection. `release.json` is RSA/SHA-256 signed; it binds the runner, Docker repository key, complete source archive, selected source files, base images and infrastructure images. The Android application verifies it before sending executable code. The remote runner verifies the same bundle before performing an action.

This is a **source-build recipe**, selected for stage 04 because upstream has no release tags or published application images. It is not an upstream release signature or a claim that release security gates passed. The application commit is `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. No `latest` reference is used at installation time. Apt/apk dependencies within builds are not fully hermetic; built image IDs are recorded and checked.

## Supported host

Fresh dedicated Ubuntu 24.04 with systemd, Python 3, OpenSSL, root or passwordless sudo; amd64/arm64; 4 CPUs, nominal 8 GiB RAM and 30 GiB free disk. Existing working Docker 24+ with Compose 2+ can be reused. Conflicting distro Docker packages, incomplete Docker installations, unknown MeetSpace installations, symlinks and unsafe ownership are rejected. Other distributions, upgrades and shared hosting require a separate reviewed recipe.

Use a real public domain with one direct IPv4 A record pointing to the VPS. No CDN, AAAA record or IP-only certificate flow. The user must accept Let's Encrypt terms and permit transmission of the ACME email. Cloud firewall must allow TCP 80/443/3478/5349/50010 and UDP 3478/50010/49160–49259. Docker port publishing can bypass UFW. PostgreSQL, Redis, RabbitMQ and S3 have no host port bindings.

## Protocol and lifecycle

The fixed SSH command starts Python as root (or through `sudo -n`) without PTY or forwarding. The request is JSON data inside a bounded base64 bootstrap. Allowed actions: `plan`, `apply`, `status`, `cancel`, `resume`. Responses are bounded `MSI1` JSON. Only fixed error codes and phase metadata cross back to Android; subprocess output and secrets do not.

`plan` changes no installation state and is valid for 15 minutes. `apply` requires its exact SHA-256, Docker consent when necessary, ACME consent, and a UUID v4. Preconditions are rechecked while holding the control lock. Android saves the public ticket before sending `apply`; an ambiguous SSH result is followed by `status`, never an automatic second installation.

The systemd job continues independently of SSH. State/events are fsync/rename checkpoints under `/opt/meetspace/jobs/<UUID>`. The worker holds a separate flock. Cancellation is durable and waits for a command boundary; pulling/building one image can take up to 15/60 minutes. It does not kill apt, migrations or database processes. Completed phases are retained. A reboot/dead worker becomes `needs_recovery`; resume is explicit and uses the original plan. The same completed job is idempotent.

Only public endpoint/domain/email/plan/job metadata are stored in Android DataStore. Password/key/passphrase exist only in memory while the wizard is open and are discarded on detachment/failure/completion. Process death requires reauthentication. SSH credentials are unrelated to MeetSpace HTTP credentials. Backups of Android app data remain disabled.

## Server layout and recovery

- `/opt/meetspace/installer/v1`: verified bundle, root owned.
- `releases/<commit>`: checksum verified source; no user data.
- `config/server.env`: server-generated independent secrets, mode 0600. Never copy into tickets or reports.
- `config/compose.json`, `config/proxy`, `config/turnserver.conf`: generated service configuration. The proxy directory is mounted as a directory so atomic replacement is visible during reload.
- `jobs/<UUID>/state.json`, `plan.json`, `images.json`, `evidence.json`: job checkpoints and readiness evidence.
- `backups/<UUID>-before-migrations.dump` and `.json`: PostgreSQL dump/checksum before migrations. Local, not offsite.
- Docker volumes `meetspace_postgres`, `meetspace_rabbitmq`, `meetspace_objects`; recordings directory; ACME account and TLS directory.

No failure/cancel/resume path deletes volumes, regenerates existing secrets, runs `compose down -v`, or rolls back committed data. Backend migrations use a transaction, advisory lock and checksum ledger. Failure leaves recoverable state. Diagnose the fixed error code, correct DNS/ports/disk/permissions, then reconnect and explicitly resume. Preserve the job directory, environment, TLS/account state, database/object volumes and recordings. Before manual recovery, take a new offsite backup and verify restoration into a separate database; the initial dump is not a backup of future user data. Automatic updates, downgrades and migrations of unknown existing installations are deliberately unsupported.

TLS renewal is a systemd timer twice per day with jitter. A changed certificate reloads Nginx and restarts Coturn; TURN connections can be briefly affected. Check `systemctl status meetspace-tls-renew.timer meetspace-tls-renew.service`. There is no remote monitoring or offsite backup service configured by this stage.

## Maintainer verification

```sh
python3 -m unittest discover -s installer/tests -v
openssl dgst -sha256 -verify installer/v1/trust-root.pem \
  -signature installer/v1/release.sig installer/v1/release.json
```

After reviewing changes, re-sign using an explicitly supplied protected private key:

```sh
python3 installer/tools/sign_release.py --key /protected/path/release-signing-private.pem
```

The key must never enter Git, APK assets or a server payload. Stage 04's local development signing key is protected under the ignored `.audit/installer-stage04/` directory; maintainers must arrange durable protected custody before distributing production builds. APK release signing is a separate responsibility. Do not edit a deployed bundle or job journal to bypass a changed plan/hash; use a separate upgrade design for future releases.

`tools/bootstrap.py` generates public SSH stdin for a supplied action/request JSON. It does not supply SSH credentials, trust unknown keys, or authorize a deployment. The Android UI is the normal operator interface.

Integration testing uses a **separately signed** `integration-v1` bundle and a Pebble ACME CA trusted only inside the disposable VM. The production APK assets contain only `source-v1` with the public Let's Encrypt directory. The fixture factory exists only in debug source sets. Public CA issuance, external cloud firewall reachability, and media end-to-end are distinct checks and are not implied by local integration success.
