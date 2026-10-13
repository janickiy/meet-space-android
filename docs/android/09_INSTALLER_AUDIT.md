# Этап 09 — аудит SSH, удалённого установщика и предложение CI

Дата: 2026-10-11. Первоначальный аудит выполнен read-only до общего baseline; после сохранения fault evidence разрешено минимальное исправление initial apply. Итог исправления приведён отдельно ниже. Изучены фактические `core/ssh`, `InstallerViewModel`, `MainActivity`, подписанный `installer/v1`, тесты и evidence этапов 03/04. Production, VPS, SSH-ключи пользователя, systemd и существующие контейнеры не изменялись; Gradle и эмулятор в этой подзадаче не запускались. Рекомендация модели из приложенного задания не является командой сменить модель.

## Измеренный локальный baseline

Условия: macOS 27.0.1 ARM64, Python 3.13.0. Полные SHA-256 пяти файлов bundle и проверка их совпадения с текущим debug APK записаны в [installer-baseline.json](evidence/09/installer-baseline.json). Это исходный baseline до исправления. Последующие изменения runner/manifest/signature и сохранение signing identity записаны отдельно в `installer-after.json`.

| Проверка | Результат | Что именно установлено |
| --- | --- | --- |
| `python3 -m unittest discover -s installer/tests -v` | 23/23 PASS | Подпись/checksum, JSON/input validation, архивные пути, права файлов, checkpoint, Compose/public ports, фиксированные ошибки. [Лог](evidence/09/installer-python-baseline.log) |
| OpenSSL RSA/SHA-256 verification | Verified OK | Текущий `release.json` соответствует `release.sig` и public trust root |
| Сравнение assets debug APK с `installer/v1` | 5/5 совпали | Установщик включается через assets sourceSet, а не отдельные копии в `app/src/main/assets` |
| Локальная проверка протокола в TemporaryDirectory | 5/5 PASS | Настоящий `flock` и освобождение; atomic rename failure сохраняет прежний файл и убирает temp; 100 записей журнала оставляют 64 события; остановленный worker даёт `needs_recovery`; cancel не затирает checkpoint. [Evidence](evidence/09/installer-local-protocol.json) |
| Инъекция двух ошибок записи при первом apply | 2/2 воспроизведения | Подтверждён разрыв начальной транзакции; это обнаружение дефекта, не успешная установка. [Evidence](evidence/09/installer-fault-baseline.json) |

Изолированные Python probes используют реальные функции runner, но подменяют root ownership guard и сведения о доступности systemd; всё пишется в новый автоматически удаляемый TemporaryDirectory. Docker, apt, systemd, DNS и SSH из probes не вызываются. Поэтому эти результаты не доказывают Linux permissions, полноценное развёртывание, восстановление БД или отсутствие orphan-процессов после SSH-разрыва.

## Подтверждённый дефект восстановления

**INS-09-01, средняя важность: первый apply не атомарен на уровне задания.** В `installer/v1/runner.py`, функция `apply`, создаются job directory, bundle и `plan.json`, затем `managed.json`, затем `state.json`. Если запись/процесс прерывается между ними:

| Точка остановки | Состояние на диске | Повтор с тем же UUID / status |
| --- | --- | --- |
| Перед `managed.json` | Job plan и bundle есть, marker/state отсутствуют | `EXISTING_INSTALLATION` / `STATE_UNAVAILABLE` |
| Перед `state.json` | Marker и job plan есть, state отсутствует | `STATE_UNAVAILABLE` / `STATE_UNAVAILABLE` |

Worker ещё не запускается, пользовательские данные не удаляются. Однако обычные reconnect/resume и `refreshMissingPlan` не устраняют проблему: требуется ручной разбор неполного журнала. Это противоречит полной автоматической восстановимости initial apply, но не является обнаруженным обходом host-key pinning или повреждением существующей БД.

Минимальная будущая коррекция после baseline gate: атомарно публиковать полностью подготовленный начальный layout либо вводить проверяемый initialization receipt с exact job/plan/release hash и восстановлением только собственной неполной установки. Не удалять произвольный неизвестный `/opt/meetspace` и не считать его безопасным по одному имени. Нужны fault tests для каждого durable write, повторного UUID, конфликтующего UUID и чужого каталога. Изменение подписанного runner требует review, новой подписи/manifest checksums и проверки политики совместимости уже выданных job tickets; тихо заменять развёрнутый рецепт нельзя. После baseline gate реализована атомарная публикация, описанная в следующем разделе.

## Исправление INS-09-01 после baseline

`initialize_layout` теперь создаёт полный bundle, plan, state и managed marker в новом mode-0700 каталоге рядом с ROOT. Файлы и все созданные каталоги fsync выполняются до публикации; затем same-filesystem `os.replace` атомарно заменяет отсутствующий либо пустой ROOT, и fsync его родителя закрепляет rename. Control flock и проверки symlink/ownership сохранены. Непустой неизвестный ROOT отклоняется без изменения данных. Worker запускается только после появления полного журнала. При остановке сразу после публикации доступны status и отдельный явный resume; обычный повтор apply не запускает второй worker.

Обычная ошибка автоматически убирает только staging-каталог этого вызова. При принудительном завершении процесса до публикации может остаться root-only staging с **публичным** bundle/plan, без `server.env` или пользовательских данных; новый apply не блокируется. Такой каталог не удаляется автоматически по одному префиксу имени. Неизвестные/неполные каталоги от старого runner по-прежнему не присваиваются автоматически: исправление предотвращает новое повреждённое начало, а не выполняет опасный ремонт произвольного legacy layout.

Результат: **23 прежних + 8 новых Python-тестов PASS**, повтор подписи **Verified OK**, исходный `trust-root.pem` побайтно сохранён. Новые tests проверяют восемь точек ошибки записи, abrupt `os._exit` в дочернем процессе после каждой из восьми записей и после rename, полный/private layout, cleanup при обычной ошибке, пустой ROOT, unknown directory, symlink самого ROOT/родителя, конфликт UUID и idempotent apply. Это настоящие локальные файловые операции и процессы, но не power-loss VM/полный Docker deployment. [After evidence](evidence/09/installer-after.json), [исходные две fault точки после исправления](evidence/09/installer-fault-after.json), [regression log](evidence/09/installer-initialization-after.log).

Изменены `installer/v1/runner.py`, производные `release.json`/`release.sig`; существующий private key использован только существующим signing tool и не копировался в source/assets/evidence. Recipe hash закономерно изменён, signer identity и pinned backend commit прежние. `installerVersion=1.0.0` оставлен как exact protocol constraint Android/runner; `builtAt` — прежний Docker BUILD_TIME закреплённого backend. Новая версия самого recipe различается проверяемыми runner/release SHA и временем after evidence, а не подменяет backend provenance. Уже развёрнутые рецепты и старые tickets не переписываются; текущий протокол отклоняет несовпадающий recipe hash, автоматический upgrade/миграция старого job не добавлялись. Финальный artifact verifier подтвердил совпадение всех пяти assets в debug APK, release APK и AAB с текущим подписанным recipe: [artifact-verification.json](evidence/09/artifact-verification.json).

## Lifecycle и безопасность SSH

- `JschSshTransport` создаёт отдельные JSch identity repository, session и exec channel для операции. Есть exact host/port pin, сравнение ключа через `MessageDigest.isEqual`, обязательный strict KEX; inspection прерывается до USERAUTH. Пароль/ключ не отправляются неизвестному или изменившемуся host key.
- Cancellation закрывает TCP socket прежде channel/session, вызывает `future.cancel(true)`; `attachSocket/Session/Channel` учитывают уже отменённую операцию. Внешние сроки: preflight 45 s, installer request 60 s; handshake/socket/channel — 15 s. `finally` обнуляет operation credentials и удаляет JSch identities. Потоки ключей ограничены 256 KiB и закрываются при отмене.
- Две daemon-нити `MeetSpace-SSH` — общий bounded worker pool процесса. Их существование после операции само по себе не является утечкой. Динамические heap/FD/thread measurements 100+ реальных SSH циклов в этом аудите **not measured**; ранее выполненный live SSH этап 03 не заменяет новый стресс-прогон.
- Stdout и stderr ограничены; stderr отбрасывается, JSch logger выключен. Наружу выходят curated сообщения/коды. Android DataStore содержит только public host pins и job ticket, без SSH credentials. Полное стирание всех внутренних immutable String/JCA/JSch копий из heap не доказано; исходные ByteArray очищаются.
- `InstallerViewModel` сохраняет ticket перед apply. После неопределённого ответа предусмотрено чтение status, а не автоматический новый install. Detach отменяет только локальное наблюдение, очищает access и сохраняет public ticket; удалённый worker продолжает работу. Cancel — отдельный явный серверный сигнал.

**OBS-09-02, baseline до исправления: фоновый polling не был привязан к STARTED.** `InstallerViewModel.observe()` делает status через отдельный SSH каждые 3 s в `viewModelScope`; `MainActivity` управляет lifecycle messaging, но не installer. Пока Activity/VM живы, переход Home сам по себе не прекращает наблюдение и хранение SSH access. Статусы `cancelled`/`needs_recovery` останавливают polling, но access остаётся в памяти для явного resume до detach/reconnect/exception/completion. Baseline unit probe реально выполнен: за 120 секунд виртуального времени — 40 status вызовов; после detach новых вызовов нет. [Архив baseline JUnit](evidence/09/installer-poll-baseline.xml). Это подтверждённый lifecycle дефект наблюдения, но не измерение 40 настоящих SSH handshakes, CPU или battery; эти показатели остаются **not measured**.

После baseline добавлен минимальный foreground gate: `MainActivity.repeatOnLifecycle(STARTED)` включает наблюдение, STOPPED отменяет только status loop; remote job, выполняющийся явный apply и retained access не отменяются. Возвращение запускает один loop, после поворота сохраняется прежний access. Отдельный observation epoch отвергает поздние результаты/`finally` старого loop. Завершение apply в фоне сохраняет ticket/status и ждёт foreground. Добавлены пять regressions (120s background/foreground, duplicate callbacks, background apply, noncooperative stale observer, terminal state). Production VM/Main изменены с разрешения после baseline. Общий Gradle build завершился успешно; `InstallerViewModelTest` прошёл 13/13, включая все пять новых foreground regressions. [JUnit after](evidence/09/jvm/app/TEST-com.meetspace.app.InstallerViewModelTest.xml).

## Удалённый worker, данные и recovery

| Область | Реализованное поведение | Граница доказательства |
| --- | --- | --- |
| Блокировки | Control flock в `/run`, отдельный worker flock; FD закрывается в `finally` | Оставшийся обычный `.lock` файл не означает удерживаемую блокировку; реальная VM/reboot повторно не проверялась |
| Разрыв SSH | Detached systemd job, durable status/checkpoints, worker inactive → `needs_recovery`, не success | SSH orphan count в новом прогоне не измерен; stage04 содержит прежний drop/reconnect evidence |
| Cancel/retry | Durable cancel-file, остановка на границе команды, сохранение completed phases; явный resume | Build может длиться до 60 min, pull до 15 min; это не мгновенная остановка и не kill БД/migration |
| Idempotency | Тот же completed UUID возвращает status; image IDs/recipe labels сохранены; secrets повторно используются | Initial apply fault исправлен локально и покрыт regressions; systemd/daemon partial failures нужны на VM |
| Секреты/временные файлы | `server.env` и секретные конфиги 0600 в root-only каталогах; atomic temp удаляется в `finally`; backup/download `.partial` очищаются при обычной ошибке | SIGKILL/power loss может оставить private temp; произвольные рабочие secrets на сервере намеренно не удаляются при cancel |
| Backup | Перед migration — `pg_dump -Fc`, fsync, checksum и `pg_restore --list`, receipt `restoreDrillPerformed=false` | `--list` не является восстановлением; это local dump, не offsite backup и не backup всех media/object данных |
| Upgrade/rollback | Неизвестная установка, другой commit/recipe/domain отклоняются; нет `compose down -v`, удаления томов, automatic downgrade/data rollback | Upgrade/rollback не реализованы и не должны рекламироваться как поддержанные |
| Миграции | Backend transaction/advisory lock/checksum ledger; retry ожидает обнаруженный контейнер migration | Go/backend код не изменялся; новый go test/race/vet в этой подзадаче не запускался |

Сохранённые проверки этапа 04 включают restore начального dump в отдельную БД, drop SSH, cancel/resume, повторный apply/migrator и реальный pinned Android SSH. Это **исторические результаты**: [04_REMOTE_INSTALLER_TESTING.md](04_REMOTE_INSTALLER_TESTING.md), [evidence/04](evidence/04/). Часть helper-скриптов этапов 03/04 находилась только в `.audit`; после очистки VM, credentials и ключи отсутствуют. Воспроизводимость полноценного нового rehearsal пока требует пересоздания одноразовой VM и отдельного integration signer по инструкции, а не повторного обращения к старому VPS.

## Воспроизводимые локальные команды

Из корня Android, без elevated privileges:

```sh
python3 -m unittest discover -s installer/tests -v
openssl dgst -sha256 -verify installer/v1/trust-root.pem \
  -signature installer/v1/release.sig installer/v1/release.json
python3 testing/stage09/installer_local_protocol_checks.py
python3 testing/stage09/installer_initialization_probe.py
python3 -m unittest discover -s testing/stage09 -p 'test_installer_initialization.py' -v
```

После baseline gate два audit harness перенесены в `testing/stage09`, добавлен `test_installer_initialization.py`. Они не запускают host services, а подменяют только host facts/root-UID boundary и systemd liveness; symlink/mode checks в regression suite остаются настоящими. Начальные failure results сохранены отдельно и не переписаны. На момент подготовки этих harness `.github` в этой подзадаче не менялась.

Полный следующий disposable VM rehearsal: создать новое уникальное Lima имя из `installer/tests/ubuntu24-arm64.yaml` (без mounts, host keys и agent forwarding), отдельный одноразовый integration signer/Pebble CA; выполнить interrupted initialization, cancel на каждой фазе, SSH observer kill, reboot worker, disk pressure, same/different UUID retry, восстановление БД в отдельный новый target и сравнение данных. Проверить `sshd` sessions, worker/container descendants, FD count, остаточные `.partial`, lock ownership и журнал; удалить только созданные объекты по ownership inventory. Не применять эту последовательность к production или существующей пользовательской VM. Public ACME, внешний firewall и обновление работающего VPS остаются отдельными проверками.

## Предложение CI после baseline gate

На момент read-only baseline в workspace не было `.github` и Git root; workflow ещё не был установлен и CI не выполнялся. Ниже исходное предложение jobs (в том числе исторический stage08 verifier/version0.8.0), а не утверждение о выполненных jobs. Теперь создана [.github/workflows/android-quality.yml](../../.github/workflows/android-quality.yml); точные реализованные условия, stage09 verifier/version0.9.0, offline allowlist и ограничения приведены в [testing/stage09/README.md](../../testing/stage09/README.md). GitHub run пока не заявляется:

| Job / запуск | Действия | Результат/ограничения |
| --- | --- | --- |
| `unit-lint` / PR, push | JDK 17; SDK platform 36, Build Tools 35.0.0; Gradle Wrapper 8.13 с текущим distribution checksum; Python suite + OpenSSL; `./gradlew :app:testDebugUnitTest :core:data:testDebugUnitTest :core:ssh:testDebugUnitTest :core:media:testDebugUnitTest :app:lintRelease` | JUnit XML + lint reports, отдельный подсчёт skipped; no signing/SSH secrets |
| `compose-ui` / PR, push | Отдельный disposable emulator; API 26 и 36, phone/tablet size; `:app:connectedDebugAndroidTest` с whitelist Foundation/SshWizard/Installer/Meetings/Call/Messaging/Recordings/SettingsUiTest | UI assertions не считаются реальным backend/SSH. Один Gradle на checkout; не писать в одни test output dirs одновременно |
| `unsigned-artifacts` / после unit/lint/UI | `:app:assembleDebug :app:assembleRelease :app:bundleRelease`; pinned bundletool 1.18.3/hash из stage08 README; `python3 testing/stage08/verify_artifacts.py --version 0.8.0 --java-home "$JAVA_HOME" --sdk "$ANDROID_HOME" --build-tools 35.0.0` | APK/AAB/mapping/SHA256/verification report. Все четыре signing env отсутствуют. Не публиковать `.audit`, private inputs, CA или fixture dumps. Verifier сейчас пишет stage08 report — сохранять как job artifact, не коммитить поверх исторического evidence |
| `live-backend` / manual либо изолированный nightly runner | Fixtures 05–08 с реальными services, private CA и generated users; environment opt-in для JVM, stdin bridge для Android; всегда scoped cleanup | Нужны реальные images/toolchain/private fixture generation. Пропуск fixture не даёт зелёный integration gate. Backend regression только если менялся backend |
| `ssh-installer-rehearsal` / manual isolated VM | Новый disposable host, отдельный integration recipe/signer, actual OpenSSH/systemd/Docker/Pebble; сценарии выше | Никаких production IP/credentials и широкого `docker system prune`. Restore/rollback ограничения явно отражать |
| `arm64-16k` / isolated release-candidate runner | Подтверждать PAGE_SIZE=16384 и выключенную compat; JNI+recordings runtime, затем cold launch APK из текущего AAB | Не подменять этой проверкой x86_64 или физические устройства; runtime evidence привязан к SHA пакетов |
| `signed-release` / manual protected environment | Только проверенный immutable commit/artifacts; четыре `MEETSPACE_*` env через secret store; подпись, сертификат, upgrade test | Постоянный ключ предоставляет владелец. Никакого upload/publish по одному PR build |

Для workflow задать `permissions: contents: read`, concurrency group по workflow/ref, timeouts, cleanup `if: always()`, отдельные disposable workspaces/jobs, ограниченную retention артефактов. Untrusted PR не получает signer/VM credentials и не запускается на постоянном привилегированном self-hosted runner. Actions должны быть закреплены по проверенным полным commit SHA, а не оставлены на mutable tags; SHA выбирать при реализации workflow, не выдумывать. [GitHub Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use).

`gradle/actions/setup-gradle` умеет проверять Wrapper JAR и управлять кэшем; это дополнение к уже имеющемуся distribution SHA, а не проверка всех Maven dependencies. Нужны отдельная политика dependency verification/lock changes и отсутствие автодоверия новым checksum. [Gradle on GitHub Actions](https://docs.gradle.org/current/userguide/github-actions.html), [Dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html).

## Что не измерено и не объявляется завершённым

Новый VPS install/upgrade/rollback/restore rehearsal; 100+ реальных SSH циклов; SSH heap retention/FD/thread baseline→after; фактические фоновые SSH network/battery показатели; принудительная ACME renewal/rotation; public CA/external firewall; CI execution и signed release. Initial-apply recovery gap исправлен и локально перепроверен; без нового отдельного VM rehearsal нельзя обозначать удалённое развёртывание как полностью проверенное production-ready.

## Дополнение: корректность Gradle test inputs

В первоначальной общей сборке `core:ssh:testDebugUnitTest` оказался `UP-TO-DATE`: `InstallerProtocolTest` читает `installer/v1` напрямую, и прежняя конфигурация не объявляла эти внешние файлы входами Test task. В `core/ssh/build.gradle.kts` добавлен минимальный `inputs.files` для пяти bundle-файлов с relative path sensitivity; тест помечен поясняющим комментарием. После этого root выполнил свежий SSH suite: **57/57 PASS**, task EXECUTED, общий build успешен за 36 s (`.audit/stage09/leakcanary-build-ssh-refresh.log`). Архив [JUnit SSH](evidence/09/jvm/core-ssh/) заменён свежими sanitized XML; история первоначального cache reuse оставлена в [jvm-summary.json](evidence/09/jvm-summary.json). Новая подписанная recipe проверена Python/OpenSSL и свежим JVM run.
