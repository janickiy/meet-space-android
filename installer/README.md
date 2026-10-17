# MeetSpace remote installer v1

Android delivers the five public files in `v1/` over an exactly pinned SSH connection. `release.json` is RSA/SHA-256 signed; it binds the runner, GitHub selection policy, Docker repository key, supported source layout, base images and infrastructure images. The Android application verifies it before sending executable code. The remote runner verifies the same bundle before performing an action.

New installations resolve the latest stable GitHub Release from `janickiy/meet-space` when the plan is requested. The release tag is resolved to its exact commit (not `target_commitish`). Only an HTTP 404 from the latest-release endpoint permits fallback to the repository's default branch; the fallback uses version `0.0.0-source.<short SHA>`. Network, rate-limit, invalid metadata and incompatible Dockerfiles fail closed instead of silently installing the embedded historical snapshot.

Before confirmation, the runner downloads the exact commit archive, computes SHA-256, checks required source inputs and checks that all Dockerfile base images are supported by the signed recipe. The plan contains `sourceLock` (repository, release/branch ref, version, full commit, commit timestamp, immutable URL, archive checksum and required-file hashes), `recipeSha256` and `releaseSha256`. Android verifies this binding and displays the selected version, full commit and hashes. GitHub is accessed anonymously over HTTPS; the recipe signature authenticates the selection policy and infrastructure, **not an upstream release signature**.

Apply, worker, status, cancel, resume and certificate renewal reconstruct the same effective recipe from the frozen plan. They never query latest again. A moved tag, new release or interrupted SSH connection cannot change an already confirmed job. Downloaded archives are rechecked against the frozen checksum. Existing managed jobs reuse their original plan; this is a new-install workflow, not an automatic backend upgrade.

The infrastructure/base image digests and Docker package versions remain pinned by the signed recipe. A new unsupported base image or missing required input requires an installer update. Apt/apk dependencies within builds are not fully hermetic; built image IDs are recorded and checked.

## Supported host

Ubuntu **22.04 LTS (Jammy) or 24.04 LTS (Noble)** with systemd, Python 3, OpenSSL, root or passwordless sudo; amd64/arm64. Ubuntu must already be installed; the installer does not install or upgrade the OS. A new MeetSpace installation requires free service ports and no existing unknown MeetSpace installation. The baseline is **2 CPUs, 4 GB RAM and about 20 GB free disk**. Two resource profiles are selected automatically:

- `standard`: 4 CPUs, nominal 8 GiB RAM (at least 7 GiB visible to Linux) and 30 GiB free disk.
- `compact-test`: 2 CPUs, nominal 4 GB RAM (at least 3 GiB visible to Linux) and 18 GiB free disk (19.3 GB). The existing protocol name is retained for recovery compatibility. It is the compact profile for small workloads; capacity depends on participants, video quality, recording and AI work.

The plan selects the profile from actual resources, includes it in its hash, and shows compact capacity guidance before confirmation. Apply reuses this selection. Existing jobs retain their profile; a legacy plan with no profile uses standard behavior.

Compact builds remain sequential and inject active compiler settings into the generated, pinned Dockerfiles before any build-stage RUN: Go uses `GOMAXPROCS=2`, `GOFLAGS=-p=1`, `GOMEMLIMIT=1536MiB`; Node uses a 1536 MiB heap and a smaller thread pool. These are process limits/tuning, not a hard host-wide memory ceiling. Build space is checked before every pull/build; compact stops with `DISK_SPACE` below a 6 GiB reserve. No global Docker prune, daemon restart, swap changes or deletion of other workloads are performed. Compact runtime containers have reduced memory budgets and task concurrency; large product-worker temporary files use private disk storage rather than a large RAM-backed tmpfs. Recording remains available with reduced concurrency.

Existing working Docker 24+ with Compose 2+ and Buildx can be reused. The plan checks both client plugins. If compatible Docker is present without Compose or Buildx, the confirmed plan installs only the missing pinned plugins from the verified Docker repository. It does not reinstall or restart the existing daemon. The plan exposes `docker.installCompose` / `docker.installBuildx` and corresponding actions, and requires package-installation consent for either plugin. Conflicting distro Docker packages, incomplete Docker installations, unknown MeetSpace installations, symlinks and unsafe ownership are rejected. Other distributions, upgrades and automatic integration with an existing host web proxy require separate support.

The signed recipe contains `dockerPackagesByUbuntu` with separate version pins for each supported Ubuntu release and architecture. Plans freeze the actual OS version, architecture and selected packages. Jammy uses the `jammy` apt suite and Ubuntu 22.04 packages; Noble uses `noble` and Ubuntu 24.04 packages. Missing or mismatched package sets fail closed. The old `dockerPackages` map remains the legacy Noble fallback only when no OS matrix exists. Changing the OS of a saved job requires a new plan; resume cannot switch its package set silently. Docker officially supports both releases: [Docker's Ubuntu installation documentation](https://docs.docker.com/engine/install/ubuntu/).

The installer does not stop an existing host Nginx automatically. For an explicitly authorized test, stop it manually with `systemctl stop nginx` before requesting the plan. Its configuration and enabled state can remain intact. After the test, release MeetSpace's proxy ports before `systemctl start nginx`; stopping only the MeetSpace proxy container preserves application volumes and the other services. Host Nginx and the MeetSpace proxy cannot both bind host ports 80/443 without a separate proxy arrangement.

Use a real public domain with one direct IPv4 A record pointing to the VPS. No CDN, AAAA record or IP-only certificate flow. The user must accept Let's Encrypt terms. New plans do not collect an email address; Certbot registers without email. The optional `acmeEmail` protocol field remains readable for existing saved plans so their hash and recovery behavior are preserved. Cloud firewall must allow TCP 80/443/3478/5349/50010 and UDP 3478/50010/49160–49259. Docker port publishing can bypass UFW. PostgreSQL, Redis, RabbitMQ and S3 have no host port bindings.

## Protocol and lifecycle

The fixed SSH command starts Python as root (or through `sudo -n`) without PTY or forwarding. The request is JSON data inside a bounded base64 bootstrap. Allowed actions: `plan`, `apply`, `status`, `cancel`, `resume`. Responses are bounded `MSI1` JSON. Only fixed error codes and phase metadata cross back to Android; subprocess output and secrets do not.

`plan` uses temporary files for the GitHub archive but changes no installation state and is valid for 15 minutes. Android allows up to three minutes for this step and keeps the SSH session alive. `apply` requires its exact SHA-256, Docker consent when necessary, ACME consent, and a UUID v4. Preconditions are rechecked while holding the control lock. Android saves the public ticket before sending `apply`; an ambiguous SSH result is followed by `status`, never an automatic second installation.

The systemd job continues independently of SSH. State/events are fsync/rename checkpoints under `/opt/meetspace/jobs/<UUID>`. The worker holds a separate flock. Cancellation is durable and waits for a command boundary; pulling/building one image can take up to 15/60 minutes. It does not kill apt, migrations or database processes. Completed phases are retained. A reboot/dead worker becomes `needs_recovery`; resume is explicit and uses the original plan. The same completed job is idempotent.

Only public endpoint/domain/plan/job metadata are stored in Android DataStore (legacy plans may also contain their previously supplied email). Password/key/passphrase exist only in memory while the wizard is open and are discarded on detachment/failure/completion. Process death requires reauthentication. SSH credentials are unrelated to MeetSpace HTTP credentials. Backups of Android app data remain disabled.

## Server layout and recovery

- `/opt/meetspace/installer/v1`: verified bundle, root owned.
- `managed.json`: selected version, full commit, archive checksum and effective recipe hash.
- `releases/<commit>`: checksum verified source; no user data.
- `config/server.env`: server-generated independent secrets, mode 0600. Never copy into tickets or reports.
- `config/compose.json`, `config/proxy`, `config/turnserver.conf`: generated service configuration. The proxy directory is mounted as a directory so atomic replacement is visible during reload.
- `jobs/<UUID>/state.json`, `plan.json`, `source-lock.json`, `images.json`, `evidence.json`: job checkpoints and readiness evidence.
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
