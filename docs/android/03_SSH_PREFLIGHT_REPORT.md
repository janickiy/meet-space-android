# Этап 03 — SSH-подключение, ключи и preflight

Дата: 2026-10-11. План до кода: [03_SSH_PREFLIGHT_PLAN.md](03_SSH_PREFLIGHT_PLAN.md). Объём: мастер подключения и диагностика; установка/apply не реализуются и не запускались.

## Реализовано

- Новый модуль `core:ssh` с отдельной границей доверия и хранилищем публичных SSH host keys. Пароли/ключи REST-аккаунта не используются в SSH.
- Мастер: host/IP, port, user, domain → handshake без credentials → SHA-256 fingerprint и независимое сравнение → password или SAF private key/passphrase → read-only preflight → структурированные результаты. Отмена, возврат и ошибки доступны в UI; формы прокручиваются и учитывают IME/инсеты.
- TOFU + pinning точного canonical host+port. Новый ключ сохраняется атомарно только после подтверждения; чужой/сменившийся ключ блокирует auth. Экран показывает предыдущий и новый отпечатки. Удаление доверия требует отдельного подтверждения и нового сравнения.
- Учётные данные только в памяти, ByteArray очищаются в finally/cancel; JSch identity удаляется. Password/passphrase не входят в UI State/DataStore/SavedState. SAF читает до 256 КиБ без persistable permission или копирования в app storage. Публичные pins в отдельном DataStore исключены из backup общими правилами приложения.
- JSch 2.28.8 + Bouncy Castle 1.86; системный crypto provider Android не подменяется. Современные KEX/signature/cipher allowlists, strict KEX обязателен; без SSH RSA/SHA1, DSA, interactive prompts, agent forwarding или PTY. TCP/session/channel закрываются при отмене; 15 секунд connect и 45 секунд на операцию.
- Статический POSIX shell probe, параметры проходят строгую валидацию и shell quoting. Не выполняется remote config как shell и не читаются env/DB secrets. stdout ≤64 КиБ, stderr ≤16 КиБ и не показывается; неполный/повреждённый протокол не считается успехом.
- Проверки Linux Ubuntu/Debian, amd64/arm64, CPU/RAM/disk, UID/`sudo -n`, Docker/Compose и daemon privileges, конфликтов HTTPS/TURN/media ports, существующих известных путей/контейнеров. DNS с VM и Android; HTTPS HEAD с Android через системные CA и hostname verification, без redirects/TLS bypass. Полная внешняя firewall/NAT readiness остаётся UNKNOWN.
- PASS/WARN/BLOCKED/UNKNOWN с объяснениями. Отсутствие Docker/TLS/DNS перед установкой является видимым предупреждением; невозможность проверить не выдаётся за пройденную проверку. План установки ещё не создан, кнопки apply нет.

## Обзор SSH-библиотеки и границы форматов

Выбран поддерживаемый fork mwiede/JSch: Java 8 baseline, RSA/SHA2 и современные SSH алгоритмы; Android использует явно выбранные BC lightweight Ed25519/XDH реализации. [Upstream описание](https://github.com/mwiede/jsch). Последний проверенный release 2.28.8 опубликован 10 октября 2026 и содержит дополнительные проверки границ входящих данных; release обновляет BC до 1.86. [Release 2.28.8](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.8), [BC release 1.86](https://www.bouncycastle.org/download/bouncy-castle-java/).

Просмотрено ordering `Session.checkHost` до `UserAuthNone` в фактическом 2.28.8 source: inspection callback прерывает handshake до authentication. Повторная операция разрешает auth только при byte-for-byte совпадении публичного ключа. R8 consumer rules сохраняют JSch reflection mappings; неиспользуемые desktop SSH agents, Kerberos и внешние logging bridges исключены из keep. Minified release собирается без `dontwarn`; mapping/usage подтверждают сохранение используемых BC crypto/auth classes и удаление восьми необязательных классов.

При обзоре key parser обнаружена необходимость ограничить вычислительную работу KDF и размеры DER до вызова библиотеки. Добавлен собственный format precheck: OpenSSH v1 none/bcrypt до 64 rounds, ограниченные SSH wire fields, bounded DER для незашифрованного traditional RSA/EC PEM. Encrypted traditional PEM, PKCS8 и PuTTY отклоняются с объяснением; поддержка passphrase подтверждается encrypted OpenSSH. Это ограниченный обзор использованных путей и тесты, а не полная независимая сертификация библиотеки.

## Файлы

- `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `README.md`.
- `core/model/src/main/kotlin/com/meetspace/core/model/SshModels.kt`.
- `core/ssh/build.gradle.kts`, `core/ssh/consumer-rules.pro`.
- В `core/ssh/src/main/kotlin/com/meetspace/core/ssh/`: `SshPreflightRepository.kt`, `DefaultSshPreflightRepository.kt`, `HostPinStore.kt`, `JschSshTransport.kt`, `PrivateKeyPolicy.kt`, `SshInputValidator.kt`, `PreflightProbe.kt`, `PreflightAnalyzer.kt`, `NetworkReadinessProbe.kt`.
- `app/src/main/java/com/meetspace/app/SshWizardViewModel.kt`, `ui/SshWizardUi.kt`; интеграция в `MainActivity.kt`, `MeetSpaceApplication.kt`, `ui/FoundationUi.kt`.
- SSH unit suites: `SshTransportPolicyTest`, `PrivateKeyPolicyTest`, `SshInputValidatorTest`, `PreflightAnalyzerTest`; app `SshWizardViewModelTest`.
- Instrumentation: `SshWizardUiTest`, `SshLiveIntegrationTest`, `SshWizardLiveUiTest`; прежний Foundation suite сохранён.
- Этот отчёт, план, [агрегированные результаты](03_VERIFICATION.json), [инструкции испытаний](03_SSH_TESTING.md) и пять screenshots; disposable fixture/tools только в ignored `.audit/ssh-stage03`.

## Фактические проверки

Android: ARM64 эмулятор API 36, планшетное окно 1280×800 dp. Реальный SSH: отдельная новая Lima/VZ VM, Ubuntu 24.04.5 ARM64, OpenSSH 9.6p1, 2 CPU / 2 GiB RAM / 12 GiB disk; без host mounts и пользовательских SSH keys. В fixture использован reserved домен `stage03.test`: отсутствие DNS/TLS ожидаемо отображается как предупреждение. Малые ресурсы и отсутствие Docker/Compose не выдаются за готовность к установке.

| Проверка | Фактический результат |
| --- | --- |
| Debug APK, test APK, unsigned minified release | Сборка успешна, версия 0.3.0 |
| JVM | 91/91: core:ssh 45, core:data 29, app 17; failures/errors 0 |
| Lint Debug | 0 ошибок, 23 предупреждения о новых версиях AGP/зависимостей |
| Compose | 16/16: Foundation 10, SSH 6; после последнего исправления SSH suite повторён, 6/6 |
| Реальный SSH repository | 7/7: NEW без auth; password + реальные факты Linux; encrypted RSA-3072; encrypted Ed25519; неверный пароль; CHANGED; отмена зависшего handshake |
| Реальный UI + SSH | 1/1: welcome → адрес → независимо сверенный fingerprint → пароль → Linux results → пересоздание Activity → выход; повтор после финального исправления успешен |
| Отдельное доказательство pinning | NEW и CHANGED запускались отдельно: изменение счётчика Accepted/Failed password/publickey в выделенном sshd log равно 0 в обоих случаях |
| Read-only preflight | Сравнение SSH/sudo configuration hashes и package manifest до/после repository suite: изменений нет |

Найденные на промежуточных запусках проблемы устранены:

- Encrypted RSA/Ed25519 сначала завершались NPE: Java JSch вызывает `Identity.setPassphrase(null)` после расшифровки. Nullable override исправлен; оба настоящих key auth затем прошли.
- Release R8 сначала остановился на отсутствующих desktop/JGSS/logging dependencies. Consumer keep сужен до Android-путей; debug/test/release и проверка reflection mappings прошли.
- Независимый review обнаружил, что отмена повторного handshake после `HOST_KEY_CHANGED` могла вернуть форму AUTH с прежним trust. Transport продолжал блокировать auth, но UI теперь сразу инвалидирует identity и выходит из AUTH; отдельный regression test подтверждает отмену и отсутствие повторного preflight.
- Первый Compose-прогон остановился при завершении test Activity (`PAUSED` вместо `DESTROYED`). Причина отдельно не доказана; после cold reboot эмулятора все 16 тестов прошли, последующий SSH-прогон также успешен. Скриншоты синхронизированы с завершением Compose rendering.

Текущие APK:

- `app/build/outputs/apk/debug/app-debug.apk`: 28 625 583 байта, SHA-256 `47b820ddf46249da1f49eadf06f1fcced7efcda27ee02f2d2b452a7170b4f158`.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: 2 307 294 байта, SHA-256 `a550f6467872dac464dcdd1d51117b066376b006872f0cb66e0758cef585beea`.

Скриншоты реального мастера: [адрес](screenshots/stage03-details.png), [неподтверждённый отпечаток и заблокированная кнопка доверия](screenshots/stage03-fingerprint.png), [факты Ubuntu/ARM64](screenshots/stage03-results.png). Дополнительно визуально проверена форма при ширине 420 dp и fontScale 1.5: [начало формы](screenshots/stage03-details-narrow-large-font.png), [кнопка после прокрутки](screenshots/stage03-details-narrow-large-font-scrolled.png). На auth screen с введённым паролем скриншоты не делались.

Последний secret scan: свежий logcat — 0 совпадений; 152 сохранённых log/JSON/XML/text artifacts перед cleanup — 0 совпадений с фактическими passwords/passphrases/private-key payload. Значения секретов не печатались. Временные credential files и test pins удалены из debug app и с хоста; созданные VM и SSH tunnel удалены, test ports закрыты. Исходные size/density/font settings эмулятора восстановлены, общий control identity Lima не изменён.

Sanitized журналы и proof находятся в ignored `.audit/evidence` / `.audit/ssh-stage03`; агрегированные результаты вместе с cleanup evidence — [03_VERIFICATION.json](03_VERIFICATION.json). Повторный live запуск требует новой disposable VM и новых одноразовых credentials.

## Риски и ограничения

- SSH credentials production не предоставлены. Реальные SSH проверки выполняются только на новой disposable VM; production VPS, его пакеты, firewall и Docker не менялись.
- TOFU требует проверки независимого источника. Компрометированный SSH host/root может сообщать ложные данные; fingerprint не доказывает достоверность системных фактов.
- Preliminary distro/resource matrix — оценка для будущего плана. Полный production Compose содержит несколько workers и хранилищ; пороги 4 CPU/8 GiB RAM/30 GiB свободного диска не гарантируют пригодность для нагрузки/записей.
- `sudo -n true` не доказывает разрешение всех будущих привилегированных команд. Доступ к Docker daemon отмечается как возможный root-equivalent риск.
- Свободные локальные порты не доказывают внешний TCP/UDP, TURN/WebRTC, NAT или ACME challenge. Отсутствие известных каталогов не доказывает отсутствие установки/данных в других путях.
- При отмене закрывается клиентская SSH-сессия; это не rollback. Probe read-only, команды внешних утилит ограничены `timeout`; никакие установки/миграции не запускаются.
- Секреты не сохраняются и буферы очищаются по возможности. JVM/IME могут создавать временные копии текста; абсолютное стирание всей памяти процесса не обещается. Process death теряет credentials и результаты мастера; pins сохраняются.
- Keyboard-interactive/MFA, hardware keys, SSH agent, encrypted PEM/PKCS8/PuTTY, private CA TLS не поддерживаются. Старые серверы без strict KEX отклоняются; проверки host key и TLS нельзя отключить.
- Настоящие RSA/Ed25519 ключи проверены через repository на Android; системный SAF file picker подключён, bounded stream import проверен unit-тестом. Полный путь выбора настоящего ключа через UI провайдера файлов в этой сессии не проходился. Положительный HTTPS-путь нового preflight probe на production-домене отдельно не запускался; реальные VM-проверки используют reserved test domain и ожидают предупреждение DNS/TLS.
- Minified release собран, но не подписан пользовательским release key и не запускался на устройстве. Реальные SSH/Compose проверки выполнялись на debug APK. REST-вход из этапа 02 не повторялся в этапе 03; его mock/unit/Compose regression suites прошли.
- Физические устройства, TalkBack, API 26, все производители/7–13-дюймовые размеры и реальный split-screen требуют дополнительной матрицы проверки.

## Следующий этап

Остановиться после этапа 03. Следующий этап — только по отдельному запросу. Прежде чем создавать apply: утвердить supported platform/resource/layout matrix, trusted signed release manifest и pinned image digests, задать ownership proxy/портов, backups/restore и журнал idempotent jobs. Показывать конкретный immutable plan перед отдельным подтверждением установки. Не запускать текущий hardcoded init-host script на произвольном VPS; не обходить preflight blockers, SSH pins или TLS.
