# Этап 04 — автоматическая установка MeetSpace через Docker

Дата: 2026-10-11. Этап реализован и проверен на одноразовой Ubuntu VM. Production-сервер `meeting.janickiy.com`, пользовательский backend checkout и GitHub не изменялись. HTTP-данные аккаунта пользователя для SSH не использовались. Этап 05 не начинался.

## Реализовано

Android 0.4.0 / versionCode 3 теперь управляет установкой по SSH: получает план без установки, показывает commit/версию/hash, ресурсы/порты/каталог/действия и риски, отдельно запрашивает согласия на план, Docker и ACME. Используется сохранённый exact SSH host key. Пароль, private key и passphrase находятся только в памяти открытого мастера; копии отдельных операций очищаются. Последний endpoint/plan/job ID сохраняется без секретов **до** отправки apply. После обрыва сначала запрашивается status; автоматического повторного запуска нет. Отдельные действия: закрыть наблюдение, остановить серверный job, подтвердить resume, обновить неотправленный/истёкший план, убрать завершённый ticket с устройства.

Версионированный runner `installer/v1` выполняет десять фаз: Docker, исходники, образы, конфигурация, зависимости, backup, migrations, HTTPS, сервисы, readiness. Control/worker locks, systemd detached execution, atomic/fsync checkpoints, ограниченный журнал с фиксированными error codes, cancellation на границах команд, обнаружение существующей установки и idempotent retry. Неизвестное состояние не считается успехом. Таймаут SSH переводит клиент к восстановлению, не выдавая отмену пользователем за сетевой результат.

Bundle подписан RSA/SHA-256: проверяются код runner, официальный apt key Docker, полный архив исходников, выбранные source files, Dockerfile/base/infrastructure digests. На VPS генерируются независимые секреты и фиксируются реально построенные image IDs. Запускаются PostgreSQL, Redis, RabbitMQ, API, media/recording/product/live workers, frontend, Nginx и Coturn. S3 — SeaweedFS из hardened-конфигурации фактического backend, под совместимым service name `minio`. Приватные сервисы не публикуют host ports. Исходники backend, REST/WS/Pion и migration ledger переиспользуются; бизнес-логика не дублируется.

HTTPS получает сертификат через ACME HTTP-01; WSS использует существующие proxy routes. Timer продления установлен; Nginx reload и Coturn restart предусмотрены при смене сертификата. Coturn работает UID 1000 с `no-new-privileges`, сброшенными capabilities и единственным `NET_BIND_SERVICE`, необходимым для запуска закреплённого file-capability binary. Проверяется запрет открытого relay и private peers. Перед миграциями создаётся PostgreSQL dump с checksum; автоматического удаления данных/томов и отката миграций нет.

## Закреплённый backend

Пользователь выбрал **сборку на VPS из закреплённого commit**. Новый чистый baseline: `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`; рецепт `v0.4.0-source.6ecd8b648a2a`; runner 1.0.0. Upstream tags/releases отсутствовали при проверке, поэтому tag не выдумывался. Все точные hashes/digests/package versions находятся в `installer/v1/release.json`.

Это отдельная локально подписанная source-build поставка, не upstream publisher signature и не подтверждение прохождения release security gates. Не подставлялись фиктивные approvals или результаты security scans. Базовые образы/исходники закреплены, но пакеты apt/apk внутри сборки не полностью герметичны. Готовые image IDs фиксируются после сборки. Ключ локальной разработки сохранён с правами 0600 в игнорируемом `.audit/installer-stage04/release-signing-private.pem`; перед распространением нужен управляемый процесс хранения ключа и отдельная подпись release APK.

## Фактические проверки

| Проверка | Результат |
| --- | --- |
| Python runner | 23 теста: подписи, checksum, JSON, пути/архивы, режимы файлов, DNS/порты, подтверждение, журнал, private ports, capabilities, exit status |
| Kotlin/JVM | 111 тестов, 0 ошибок; включены регрессии auth/SSH и новые installer/recovery/timeout проверки |
| Compose на планшете | 20 тестов, 0 ошибок; 4 installer cases повторены на конечном UI |
| Узкий экран / шрифт 150% | 4 installer cases, 360 dp ширина, 0 ошибок |
| Android → реальный SSH | 1 opt-in integration: независимый fingerprint, signed plan, DataStore, реальный completed status, idempotent resume, очистка пароля и отображение результата |
| Чистый Ubuntu 24.04 ARM64 VPS | Docker установлен; полный процесс установки завершился с 10/10 фазами |
| Readiness | health, точные image IDs, private ports, HTTPS API/frontend versions, отказ неавторизованному WSS, TURN TLS, Allocate/auth, запрет private peer |
| DNS failure / disk full / occupied port | Реальные NXDOMAIN, tmpfs 1 MiB и listener 80 заблокировали plan соответствующими кодами |
| SSH drop | Соединение наблюдателя принудительно закрыто; systemd job продолжил работу |
| Cancel / resume | Безопасная остановка на границе команды и продолжение с сохранёнными фазами |
| Retry / already installed | Повтор того же apply/resume возвращает completed; новый plan обнаруживает исходный job; секреты не меняются |
| Backend smoke | Реальные login, frontend, capabilities, ICE configuration, admin denial, создание/join/закрытие тестовой встречи и WSS snapshot |
| Миграции / backup | 33 записи ledger; повтор migrator не изменил число; dump восстановлен в отдельную тестовую БД |
| Секреты / файлы | Проверены private modes; сгенерированные серверные секреты не обнаружены в последних 500 строках логов сервисов |
| Renewal service | Timer enabled; отдельный запуск service завершился `Result=success`, exit 0; сертификат ещё не требовал продления |
| Сборки / lint | Debug и unsigned minified release собраны; lint: 0 errors, 23 предупреждения о версиях зависимостей |

Использована одноразовая VM 4 CPU / 8 GiB / 60 GiB без host mounts и agent forwarding. Первый прогон устанавливал Docker на пустую машину и собирал образы с нуля. Финальный полный прогон после исправлений использовал пустые тома и существующий build cache. DNS/disk/port проверки повторялись. SSH-drop/cancel/resume проверялись в ранних прогонах; их отдельные результаты сохранены с исходными job IDs. После исправлений прав/capabilities выполнены полный success, backend smoke, реальный Android SSH и повтор завершённого job.

ACME проверялся через настоящий Pebble challenge и явно доверенный CA **только внутри VM**. Проверка цепочки и hostname не отключалась. Сервисы не заменялись mock-реализациями; опциональные внешние email/push/AI providers остались выключены штатной backend-конфигурацией.

Публичные evidence: [04_VERIFICATION.json](04_VERIFICATION.json), [server readiness](evidence/04/evidence.json), [backend smoke](evidence/04/backend-smoke.json), [backup/migrations](evidence/04/runtime-verification.json), [живой экран Android](evidence/04/installer-live-tablet.png). Environment files, SSH credentials, private keys и ACME account secrets в evidence не включены. VM удалена после проверки; временный SSH-пароль, test signing key и private Android fixtures удалены. Общий ключ Lima и защищённый ключ локальной release-поставки сохранены. Размер/density/font scale эмулятора восстановлены.

## Исправленные отказы

Во время разработки обнаружены и исправлены: права apt key при umask 0077; размещение control lock вне общего writable `/run/lock`; права публичных исходников, иначе non-root migrator не читал SQL; `NET_BIND_SERVICE` для file-capability binary Coturn; directory bind mount Nginx для видимости atomic replacement; корректный nonzero CLI result для ошибок renewal; отдельная обработка network deadline и caller cancellation; восстановление истёкшего ticket, если apply не дошёл до сервера. Инструментальный тест также исправлен на JUnit `Unit` return type. Эти отказы не выдавались за успешную установку.

## Ограничения и не запущено

- Новый выделенный Ubuntu 24.04 / systemd / Python 3 / OpenSSL, root или passwordless sudo, amd64/arm64, минимум 4 CPU / номинально 8 GiB / 30 GiB свободно. Реальная матрица проверена на ARM64; amd64, другие distro, неизвестные существующие установки и in-place upgrades не проверены/не поддерживаются этим профилем.
- Только прямой публичный IPv4 и домен с A-записью без AAAA/CDN. Выдача публичного Let's Encrypt сертификата и доступность портов через внешний cloud firewall не проверялись. IP-only trusted TLS не обещается.
- Медиа audio/video end-to-end, нагрузка и реальные Android-устройства не проверялись. Проверка WSS snapshot не является проверкой видеозвонка.
- Принудительная ротация сертификата не проверялась. Coturn restart при реальном продлении может кратко прервать TURN-соединения.
- Отмена ждёт завершения текущей команды; сборка одного образа может занять до часа. Reboot требует явного reconnect/resume. Полностью/частично повреждённая начальная root metadata требует администратора; установка не перезаписывает неизвестные данные.
- Backup локальный и относится к моменту перед миграциями; offsite backup/плановое восстановление пользовательских данных настраиваются отдельно. Автоматического destructive rollback нет.
- Release APK пока **unsigned**; debug APK предназначен для проверки. Публикация, production deployment, Git commit/push/tag и security scan approvals не выполнялись. Рабочая папка Android по-прежнему не является Git checkout; пользовательский backend checkout не изменён.

## Изменённые файлы и дальнейшая работа

- `installer/v1/{runner.py,release.json,release.sig,trust-root.pem,docker.asc}`; `installer/tools/{bootstrap.py,sign_release.py}`; `installer/tests/{test_runner.py,ubuntu24-arm64.yaml}`; `installer/README.md`.
- `core/model/.../InstallerModels.kt`; `core/ssh/.../{InstallerProtocol.kt,SshInstallerRepository.kt,JschSshTransport.kt}`; debug-only fixture factory и `InstallerProtocolTest.kt`; `core/ssh/build.gradle.kts`.
- `app/.../{InstallerViewModel.kt,ui/InstallerUi.kt,MainActivity.kt,MeetSpaceApplication.kt,ui/SshWizardUi.kt}`; installer JVM/Compose/live tests; `app/build.gradle.kts` с version/публичными assets.
- `.gitignore`, `README.md`, план/отчёт/testing/verification/evidence в `docs/android`.

Для сопровождения: [контракт и recovery policy](../../installer/README.md), [повторение проверок](04_REMOTE_INSTALLER_TESTING.md). До production rollout проверить на новом целевом VPS публичный ACME, внешние TCP/UDP порты, offsite restore, amd64 при необходимости и подпись APK. Следующий Android-этап требует отдельного задания; автоматически не начат.
