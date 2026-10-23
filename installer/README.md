# MeetSpace remote installer v1

Android and Desktop deliver the same five public files in `v1/` over an exactly pinned SSH connection. `release.json` is RSA/SHA-256 signed; it binds the runner, backend source snapshot or GitHub selection policy, Docker repository key, supported source layout, base images and infrastructure images. The application verifies it before sending executable code. The remote runner verifies the same bundle before performing an action.

New Android installations offer two explicit image modes. **Ready images** is the UI default: `github-prebuilt-v1` discovers the newest published GitHub Release containing both `prebuilt.json` and the native amd64/arm64 package, including prereleases, just like backend `install.sh --mode images`. Operators can select `latest` or an exact release tag. Source-only releases and incomplete uploads are ineligible. A missing package or a network/integrity failure never starts a source build automatically.

**Build on server** retains the reviewed backend commit, source archive checksum, required files, pinned base images and sequential compact build settings in the signed recipe. The selected version and full commit are displayed before installation. Updating this reviewed source snapshot requires signing a new recipe.

Ready-image plans freeze `imageLock` (repository, version, full commit, platform, exact archive URL, byte size, archive SHA-256 and index SHA-256) and `sourceLock` for configuration files at the same commit. Apply/resume never resolve `latest` again. The worker verifies compressed bytes, safe extraction, inner archive checksum, OCI manifests/configuration digests, architecture, application source labels and every upstream offline scan/SBOM before `docker load`. HIGH/CRITICAL findings block installation. Downloaded installer scripts are never executed. These are GitHub HTTPS/checksum and upstream report checks, not a claim of an upstream cryptographic publisher signature. Certbot remains separately pinned by the signed Android recipe.

Existing Docker needs Compose for ready images; Buildx is checked only for source builds. Archive download, verification and Docker import have separate progress labels. Checked image IDs are checkpointed for resume; completed imports are reused. The source/default behavior of legacy plans without `imageMode` is preserved. A running job cannot switch modes.

The runner also retains compatibility with signed recipes using `github-latest-stable-v1`: those resolve the latest stable GitHub Release when the plan is requested, resolve its tag to the exact commit and allow default-branch fallback only on HTTP 404. Such plans contain `sourceLock` (repository, release/branch ref, version, full commit, timestamp, immutable URL and checksums), `recipeSha256` and `releaseSha256`. Source bytes and Dockerfile inputs are checked before execution in either mode. GitHub is accessed anonymously over HTTPS; no upstream release signature is inferred.

Apply, worker, status, cancel, resume and certificate renewal reconstruct the same effective recipe from the frozen plan. They never query latest again. A moved tag, new release or interrupted SSH connection cannot change an already confirmed job. Downloaded archives are rechecked against the frozen checksum. Existing managed jobs reuse their original plan; this is a new-install workflow, not an automatic backend upgrade.

The infrastructure/base image digests and Docker package versions remain pinned by the signed recipe. A new unsupported base image or missing required input requires an installer update. Apt/apk dependencies within builds are not fully hermetic; built image IDs are recorded and checked.

Docker `BUILD_TIME` is derived from the signed `builtAt` instant in UTC (`YYYY-MM-DDTHH:MM:SSZ`), including when an older recipe contains an explicit timezone offset. Conversion does not modify the frozen recipe or plan hash during resume. Invalid dates, missing timezones and fractional seconds fail before image operations. The signing tool also normalizes new recipe timestamps before signing.

## Supported host

Ubuntu **22.04 LTS (Jammy) or 24.04 LTS (Noble)** with systemd, Python 3, OpenSSL, root or passwordless sudo; amd64/arm64. Ubuntu must already be installed; the installer does not install or upgrade the OS. A new MeetSpace installation requires free service ports and no existing unknown MeetSpace installation. The baseline is **2 CPUs, 4 GB RAM and about 20 GB free disk**. Two resource profiles are selected automatically:

- `standard`: 4 CPUs, nominal 8 GiB RAM (at least 7 GiB visible to Linux) and 30 GiB free disk.
- `compact-test`: 2 CPUs, nominal 4 GB RAM (at least 3 GiB visible to Linux) and 18 GiB free disk (19.3 GB). The existing protocol name is retained for recovery compatibility. It is the compact profile for small workloads; capacity depends on participants, video quality, recording and AI work.

The plan selects the profile from actual resources, includes it in its hash, and shows compact capacity guidance before confirmation. Apply reuses this selection. Existing jobs retain their profile; a legacy plan with no profile uses standard behavior.

Compact builds remain sequential and inject active compiler settings into the generated, pinned Dockerfiles before any build-stage RUN: Go uses `GOMAXPROCS=2`, `GOFLAGS=-p=1`, `GOMEMLIMIT=1536MiB`; Node uses a 1536 MiB heap and a smaller thread pool. These are process limits/tuning, not a hard host-wide memory ceiling. Build space is checked before every pull/build; compact stops with `DISK_SPACE` below a 6 GiB reserve. No global Docker prune, daemon restart, swap changes or deletion of other workloads are performed. Compact runtime containers have reduced memory budgets and task concurrency; large product-worker temporary files use private disk storage rather than a large RAM-backed tmpfs. Recording remains available with reduced concurrency.

Go module/compiler caches and the npm download cache persist in BuildKit. Added caches are isolated by target architecture and pinned toolchain; upstream cache mounts are preserved without duplicates. Finished application images and infrastructure pulls are checkpointed by immutable image ID. Resume validates architecture and identity, skips completed work, and applies the disk reserve only before new pulls/builds. Timings for each pull/build/reuse are recorded in `jobs/<UUID>/image-timings.json`; the first cold build still needs compilation and dependency downloads.

Existing working Docker 24+ with Compose 2+ and Buildx can be reused. The plan checks both client plugins. If compatible Docker is present without Compose or Buildx, the confirmed plan installs only the missing pinned plugins from the verified Docker repository. It does not reinstall or restart the existing daemon. The plan exposes `docker.installCompose` / `docker.installBuildx` and corresponding actions, and requires package-installation consent for either plugin. Conflicting distro Docker packages, incomplete Docker installations, unknown MeetSpace installations, symlinks and unsafe ownership are rejected. Other distributions, upgrades and automatic integration with an existing host web proxy require separate support.

The signed recipe contains `dockerPackagesByUbuntu` with separate version pins for each supported Ubuntu release and architecture. Plans freeze the actual OS version, architecture and selected packages. Jammy uses the `jammy` apt suite and Ubuntu 22.04 packages; Noble uses `noble` and Ubuntu 24.04 packages. Missing or mismatched package sets fail closed. The old `dockerPackages` map remains the legacy Noble fallback only when no OS matrix exists. Changing the OS of a saved job requires a new plan; resume cannot switch its package set silently. Docker officially supports both releases: [Docker's Ubuntu installation documentation](https://docs.docker.com/engine/install/ubuntu/).

The installer does not stop an existing host Nginx automatically. For an explicitly authorized test, stop it manually with `systemctl stop nginx` before requesting the plan. Its configuration and enabled state can remain intact. After the test, release MeetSpace's proxy ports before `systemctl start nginx`; stopping only the MeetSpace proxy container preserves application volumes and the other services. Host Nginx and the MeetSpace proxy cannot both bind host ports 80/443 without a separate proxy arrangement.

Use a real public domain with one direct IPv4 A record pointing to the VPS. No CDN, AAAA record or IP-only certificate flow. The user must accept Let's Encrypt terms. The operator may provide an optional certificate-contact email; Certbot receives it only when supplied and otherwise registers without email. Existing saved `acmeEmail` values retain their original hash and recovery behavior. Cloud firewall must allow TCP 80/443/3478/5349/50010 and UDP 3478/50010/49160–49259. Docker port publishing can bypass UFW. PostgreSQL, Redis, RabbitMQ and S3 have no host port bindings.

## Protocol and lifecycle

The fixed SSH command starts Python as root (or through `sudo -n`) without PTY or forwarding. The request is JSON data inside a bounded base64 bootstrap. Allowed actions: `plan`, `apply`, `status`, `cancel`, `resume`. Responses are bounded `MSI1` JSON. Only fixed error codes and phase metadata cross back to Android; subprocess output and secrets do not.

`plan` changes no installation state and is valid for 15 minutes. The current pinned recipe does not query GitHub's latest release or download the archive during planning; legacy dynamic recipes use temporary archive files to freeze their selected source. Android allows up to three minutes for this step and keeps the SSH session alive. `apply` requires its exact SHA-256, Docker consent when necessary, ACME consent, and a UUID v4. Preconditions are rechecked while holding the control lock. Clients save the public ticket before sending `apply`; an ambiguous SSH result is followed by `status`, never an automatic second installation.

SMTP is optional and explicitly configured in the wizard. Public `options.smtp` contains exactly `host`, integer `port`, `from` (one sender mailbox), `username`, and `tlsMode` (`tls` or `starttls`). The separate top-level `smtpPassword` is accepted only by `apply`, never as plan metadata. When SMTP is enabled, the password is required before installation state is published. The complete job and root-only `config/smtp.env` are published atomically, so interruption cannot create a resumable SMTP job without its secret. Compose passes SMTP credentials only to `api` and `product-worker`; dollars, quotes and backslashes remain literal. Resume uses the private file and rejects missing, changed or over-permissive configuration. An existing legacy plan without SMTP keeps its original format and behavior. The backend requires certificate-verified TLS and SMTP AUTH PLAIN; plaintext SMTP is unsupported.

New installations use `REMINDER_OFFSETS=3h`. Invitation email is sent immediately, with one follow-up three hours before a scheduled meeting. Late invitations do not produce a duplicate immediate reminder; cancelled/started meetings and removed participants are excluded. Registered users' reminder preferences are respected. A live provider account and working domain mail authentication are still required; local installer checks cannot certify inbox delivery.

### Enable email later after skipping SMTP

Skipping SMTP does not prevent installation: email delivery stays disabled, including invitations and reminders. In-app notifications remain independent. SMTP can be added through server configuration after the installation has **completed**; do not edit a running or resumable job's plan, signed bundle or journal.

1. As root, back up `/opt/meetspace/config/compose.json` to a private file. Create `/opt/meetspace/config/smtp.env` owned by root with mode `0600` using a local editor, not a shell command containing a real password. Add `SMTP_HOST`, `SMTP_PORT`, `SMTP_TLS_MODE` (`tls` or `starttls`), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` and `SMTP_TIMEOUT=15s`. `SMTP_FROM` is the provider-authorized mailbox, optionally `MeetSpace <mailbox>`. Use double-quoted Compose env values: escape backslashes as `\\`, quotes as `\"`, and dollars as `$$`; do not allow line breaks in credentials. Do not put credentials in `server.env`, which is shared with other services.
2. In `config/compose.json`, append `/opt/meetspace/config/smtp.env` to the existing `env_file` list of **only** `services.api` and `services.product-worker`, preserving `server.env` as the first entry. Set `environment.EMAIL_PROVIDER_MODE` to `smtp` for both, preserving any other keys. All other service definitions stay unchanged. The private file alone does not enable mail until these service references are added.
3. From `/opt/meetspace`, validate without printing secrets and recreate only the two notification services. Use the immutable release directory identified by `managed.json`:

   ```sh
   cd /opt/meetspace
   meetspace_release="/opt/meetspace/releases/$(sudo jq -er .commit managed.json)"
   sudo docker compose --project-name meetspace --project-directory "$meetspace_release" \
     --env-file /opt/meetspace/config/server.env -f /opt/meetspace/config/compose.json config --quiet
   # Run only if the preceding validation succeeded; API connections may reconnect.
   sudo docker compose --project-name meetspace --project-directory "$meetspace_release" \
     --env-file /opt/meetspace/config/server.env -f /opt/meetspace/config/compose.json \
     up -d --no-deps --force-recreate api product-worker
   ```

Test with a newly created invitation to a mailbox you control; past notifications skipped while email was disabled are not replayed. Confirm provider acceptance and inbox/spam delivery. To disable email again, set the two service-specific `EMAIL_PROVIDER_MODE` values to `noop` and recreate those services. Do not rerun installation to change SMTP. For command-line `install.sh` installations, the equivalent configuration uses `config/meetspace.env` and the full Compose overlay stack documented in the backend's `docs/INSTALLATION.md`.

The systemd job continues independently of SSH. State/events are fsync/rename checkpoints under `/opt/meetspace/jobs/<UUID>`. The worker holds a separate flock. Cancellation is durable and waits for a command boundary; pulling/building one image can take up to 15/60 minutes. It does not kill apt, migrations or database processes. Completed phases are retained. A reboot/dead worker becomes `needs_recovery`; resume is explicit and uses the original plan. The same completed job is idempotent.

Only public endpoint/domain/plan/job metadata are stored in Android DataStore (legacy plans may also contain their previously supplied email). Password/key/passphrase exist only in memory while the wizard is open and are discarded on detachment/failure/completion. Process death requires reauthentication. SSH credentials are unrelated to MeetSpace HTTP credentials. Backups of Android app data remain disabled.

## Server layout and recovery

- `/opt/meetspace/installer/v1`: verified bundle, root owned.
- `managed.json`: selected version, full commit, archive checksum and effective recipe hash.
- `releases/<commit>`: checksum verified source; no user data.
- `config/server.env`: server-generated independent secrets, mode 0600. Never copy into tickets or reports.
- `config/smtp.env`: optional SMTP credentials, mode 0600, never included in public plans, status or tickets.
- `config/compose.json`, `config/proxy`, `config/turnserver.conf`: generated service configuration. The proxy directory is mounted as a directory so atomic replacement is visible during reload.
- `jobs/<UUID>/state.json`, `plan.json`, `source-lock.json`, `images.json`, `image-timings.json`, `evidence.json`: job checkpoints, image timings and readiness evidence.
- Ready-image jobs additionally retain `prebuilt.tar.gz`, verified `prebuilt/` data and `prebuilt-receipt.json` for reproducible recovery. They contain public release data, not credentials. No global cache pruning is performed.
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
