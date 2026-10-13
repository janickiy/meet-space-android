# Этап 09: результаты проверок

Этот документ фиксирует выполненные проверки версии **0.9.0 (8)**. Счётчики получены из JUnit, instrumentation JSON и lint XML, а не из числа Gradle tasks. Функциональная матрица, строгий ARM64 16 KiB runtime и финальные пакеты проверены отдельно; результаты стресс-прогонов и измерений приведены в их собственном evidence и итоговом отчёте.

## Сборка и JVM

Общая сборка завершилась **BUILD SUCCESSFUL in 1m 21s**, 505 actionable tasks: 131 executed, 374 up-to-date. Созданы debug/test APK, release APK и AAB; debug/release lint выполнен. Локальный исходный журнал: `.audit/stage09/final-build.log`. Постоянные signing credentials для дистрибуции не добавлялись; наличие задачи `signReleaseBundle` не является доказательством подписи release. Подпись и содержимое пакетов проверяются отдельным artifact verifier.

| Набор | Tests | Passed | Skipped | Failures / errors | Выполнение в этой сборке |
| --- | ---: | ---: | ---: | ---: | --- |
| app | 93 | 93 | 0 | 0 / 0 | EXECUTED |
| core/data | 107 | 107 | 0 | 0 / 0 | EXECUTED |
| core/ssh | 57 | 57 | 0 | 0 / 0 | EXECUTED, отдельный свежий запуск |
| core/media | 14 | 14 | 0 | 0 / 0 | EXECUTED |
| Всего в сохранённых отчётах | 271 | 271 | 0 | 0 / 0 | Все suite выполнены в двух успешных сборках |

`InstallerProtocolTest` читает подписанный bundle вне source set. Первоначально эти файлы не были объявлены входами Gradle Test task, поэтому SSH suite оказался `UP-TO-DATE` после изменения runner. После добавления `signedInstallerBundle` input для пяти файлов `installer/v1` выполнен отдельный свежий `:core:ssh:testDebugUnitTest`: **57/57 PASS**, общий build **BUILD SUCCESSFUL in 36s**. Лог — `.audit/stage09/leakcanary-build-ssh-refresh.log`, время нового InstallerProtocol XML — `2026-10-11T16:37:07.617Z`. Архив содержит свежий SSH XML; история первоначального cache reuse оставлена в `jvm-summary.json`. Повторный запуск включал диагностический LeakCanary debug variant; это не привязка итогового распространяемого APK к этому диагностическому варианту.

Девять live JVM tests действительно выполнены, без пропусков: `LiveMeetingsRepositoryTest` — 2, `LiveMessagingRepositoryTest` — 3, `LiveRecordingsBackendTest` — 4. Они используют одноразовые локальные backend fixtures и private CA; это не проверка production. `InstallerViewModelTest` прошёл **13/13**, включая пять новых foreground/cancellation regressions. Подробные имена, времена запуска и task execution записаны в [jvm-summary.json](evidence/09/jvm-summary.json), XML — в [evidence/09/jvm](evidence/09/jvm/).

Из первоначальных копий JUnit удалены 72 `properties`/`system-out`/`system-err` элемента; при замене SSH XML на свежий повторно удалены 15 элементов его пяти suites. При проверке использованы 22 известных private значения из 14 файлов локальных fixtures: заменять значения не понадобилось, оставшихся совпадений — 0. Значения в evidence и этот документ не включались. Это проверка доступного корпуса секретов, а не доказательство отсутствия любых неизвестных секретов.

## Installer и lifecycle

| Проверка | Результат | Evidence |
| --- | --- | --- |
| Прежний Python suite installer | 23/23 PASS | [installer-python-after.log](evidence/09/installer-python-after.log) |
| Новые initialization regressions | 8/8 PASS; 8 write-fault и 9 abrupt-exit границ внутри сценариев | [installer-initialization-after.log](evidence/09/installer-initialization-after.log) |
| Локальные flock/journal/cancel/recovery проверки | 5/5 PASS | [installer-local-protocol-after.json](evidence/09/installer-local-protocol-after.json) |
| Подпись нового recipe | Verified OK, доверенный public key прежний | [installer-after.json](evidence/09/installer-after.json) |
| Фоновый polling до исправления | 40 status вызовов за 120 s виртуального времени | [installer-poll-baseline.xml](evidence/09/installer-poll-baseline.xml) |
| Foreground gate после исправления | 13/13 VM tests; фон не опрашивается, foreground возобновляет один loop | [InstallerViewModelTest.xml](evidence/09/jvm/app/TEST-com.meetspace.app.InstallerViewModelTest.xml) |

Filesystem/process tests действительно выполняют локальные file writes, fsync, flock и abrupt child termination. Host facts, root UID boundary и systemd liveness изолированы. Они не развёртывают VPS и не подтверждают реальное восстановление после отключения питания. Baseline/after и ограничения cancel, backup, upgrade/rollback изложены в [09_INSTALLER_AUDIT.md](09_INSTALLER_AUDIT.md).

Финальный повтор Python suites: **39/39 PASS** — 23 прежних installer tests и 16 stage09 tests (8 initialization + 8 artifact verifier). Источники и SHA локальных журналов сохранены в [python-final-summary.json](evidence/09/python-final-summary.json). Пять отдельных protocol checks выше не прибавляются к этим 39 unit tests.

## Lint

Debug и release отчёты app содержат **0 errors, 46 warnings каждый**. Это одни и те же предупреждения двух вариантов; их нельзя складывать как 92 независимые проблемы.

| Тип | Количество в app на вариант |
| --- | ---: |
| ApplySharedPref | 8 |
| AndroidGradlePluginVersion | 3 |
| GradleDependency | 12 |
| NewerVersionAvailable | 10 |
| UseKtx | 9 |
| UseTomlInstead | 4 |

В core/data, core/ssh и core/designsystem — 0 errors / 0 warnings. В core/media — 0 errors / 2 warnings на вариант (`NewerVersionAvailable`, `UseTomlInstead`). Строгое объединение по id, severity, полному сообщению и location даёт 48 записей во всех модулях/вариантах; это механическое объединение отчётов, а не число уязвимостей. Предупреждения не подавлялись для получения зелёного результата. [lint-summary.json](evidence/09/lint-summary.json).

## Воспроизведение и CI

Команды Python/OpenSSL и границы disposable VM rehearsal приведены в [аудите installer](09_INSTALLER_AUDIT.md). Портативный artifact verifier, окружение JVM/SDK и подготовленный workflow описаны в [testing/stage09/README.md](../../testing/stage09/README.md). Локальные проверки workflow/verifier выполнены отдельно; успешный запуск GitHub Actions не заявляется.

Сборку/тесты, изменяющие одни и те же Gradle outputs, запускают последовательно. Для свежих real-backend JVM tests нужно явно включить документированные fixtures; отсутствие fixture и skipped test не считается успешной интеграционной проверкой. Production, сторонние Docker containers и существующие VPS этими локальными checks не менялись.

## Runtime и окончательные артефакты

| Проверка | Итог | Evidence |
| --- | --- | --- |
| Tablet API36 ARM64, offline UI и JNI smoke | 77 PASS, 0 skipped: 76 UI + 1 native smoke | [tablet-offline](evidence/09/android-runs/tablet-offline.json) |
| Tablet, реальные recordings/backend/SAF/onboarding | 4/4 PASS | [tablet-recordings](evidence/09/android-runs/tablet-recordings.json) |
| Tablet, реальные HTTPS/WSS чаты, файлы и уведомления | 5/5 PASS | [tablet-messaging](evidence/09/android-runs/tablet-messaging.json) |
| Tablet, реальные camera/relay/reconnect, screen sharing, ICE retry и потеря сети | 4/4 PASS после test harness fix | [tablet-call-media](evidence/09/android-runs/tablet-call-media.json) |
| Tablet, отказ в CAMERA permission и listening | 1/1 PASS | [tablet-call-permission](evidence/09/android-runs/tablet-call-permission.json) |
| Регрессия cleanup после invalid ICE configuration | 1/1 PASS | [after-ice-regression](evidence/09/android-runs/after-ice-regression.json) |
| Notification regression, POST_NOTIFICATIONS изначально не выдан | 1/1 PASS после удаления in-process revoke | [notification-permission-regression](evidence/09/android-runs/notification-permission-regression.json) |
| Phone 1080×1920, density 480, font scale 1.5: Foundation/Recordings/Settings UI | 25 PASS, 1 conditional skip, 0 failures | [phone-large-font-ui](evidence/09/android-runs/phone-large-font-ui.json) |
| Тот же phone: реальные recordings/SAF/onboarding | 4/4 PASS | [phone-large-font-recordings](evidence/09/android-runs/phone-large-font-recordings.json) |
| Strict 16 KiB ARM64 API36, финальный debug APK: JNI + recordings | 5/5 PASS, compatibility modes отключены | [final-strict-16k-native-recordings](evidence/09/android-runs/final-strict-16k-native-recordings.json), [device](evidence/09/strict-16k-device.json) |
| Strict 16 KiB ARM64, реальный camera/mic/relay/reconnect call | 1/1 PASS на обычном debug APK с побайтно совпадающим содержимым финального пакета | [strict-16k-call](evidence/09/android-runs/strict-16k-call.json), [эквивалентность 290 ZIP entries](evidence/09/normal-rebuild-equivalence.json) |
| Release из финального AAB, временная test signature: cold launch и настоящий split-screen до ширины 432 dp | PASS; обе onboarding actions видны, connect form открыт | [runtime-16k](evidence/09/runtime-16k.json), [release-multiwindow](evidence/09/release-multiwindow.json), [скриншот узкого окна](evidence/09/release-split-narrow-16k.png) |

В строках instrumentation выше **92 различных passed test methods**, считая имена `class + test` и исключая повторы между tablet/phone/16 KiB и notification regression. Один phone skip относится к `tabletUsesTwoPanesAndResizeKeepsSelectedDetailInSinglePane`: его условие требует tablet, на tablet этот метод прошёл. Это не подсчёт stress cycles, JVM tests или незавершённых дополнительных измерений.

Финальный artifact verifier завершился **`STATIC_PACKAGING_CHECKS_PASSED_WITH_RELRO_FINDINGS`**. Debug APK имеет debug signature; release APK и AAB подтверждены как **unsigned**. Все пять installer assets во всех трёх пакетах совпали с текущим подписанным recipe. [artifact-verification.json](evidence/09/artifact-verification.json) содержит размеры, полные SHA, подписи, manifest, native alignment, license и private-material checks. `runtime16KiBVerifiedForAbi.arm64-v8a=true`, `x86_64=null`: невыравненные GNU_RELRO окончания остаются отражены в отчёте, успех выбранных ARM64 операций не доказывает все native paths/ABI/devices. API36 использует платформенный PathIterator; native graphics path operations этим smoke не подтверждены. Release smoke проверял запуск и неавторизованный onboarding, не release login/playback или постоянный ключ владельца.

### Первоначальные сбои и их закрытие

Первый `tablet-messaging` запуск на свежем AVD был прерван после одного PASS: `LiveMessagingTest` отозвал `POST_NOTIFICATIONS` в собственном `finally`, и ActivityManager завершил процесс приложения с причиной `permissions revoked` (19:42:13). Это ошибка teardown теста. Первичный [failure result](evidence/09/android-runs/tablet-messaging-initial-permission-failure.json) сохранён отдельно; runner не объявляется успешным по одному пройденному методу. In-process revoke удалён, очистка уведомлений сохранена. Разрешение выдаётся только для необходимого сценария; восстановление исходного разрешения выполняет test host после завершения instrumentation либо удалением созданного им disposable AVD. Закрыто повтором 5/5 messaging и отдельной регрессией 1/1 с исходно невыданным разрешением.

Первая попытка artifact verifier остановилась на разборе AAB manifest: JVM пишет служебное уведомление о `JAVA_TOOL_OPTIONS` в stderr, прежний helper присоединял его к XML stdout. Отдельный фактический `bundletool dump manifest` подтвердил валидность stdout и невалидность склеенного вывода; parsing теперь использует только stdout. Добавлены три regressions для stderr notice, malformed XML и nonzero exit, без вывода содержимого manifest/окружения: **8/8 verifier tests PASS**. Закрыто успешной полной проверкой финальных пакетов после восстановления обычного debug APK, включая hash-bound 16 KiB runtime evidence. [Parser evidence](evidence/09/artifact-parser-fix.json).

Первый `tablet-call-media` завершился с 3/4 PASS: `unreachableTurnFailsAndRetryRecovers` вызывал `retry()` и сразу применял fail-fast ожидание к прежнему `FAILED`. Read-only проверка `CallEngine` подтвердила, что retry ставит connect в очередь actor, а `CONNECTING` публикуется асинхронно. В тест добавлено отдельное ожидание нового state snapshot не более 10 s; новый `FAILED` отклоняется, дальнейшее ожидание реального CONNECTED+relay остаётся строгим. Fault flag `forceIceFailure` сбрасывается в `finally`, включая ошибки до retry. Production-код по этому сбою не изменён. [Первичный failure](evidence/09/android-runs/tablet-call-media-initial-retry-race.json) сохранён; закрыто реальным повтором 4/4 на исправленном test APK.

Stress/performance, LeakCanary и auth-idle проверки учитываются отдельно в итоговом отчёте и [measurement-summary.json](evidence/09/measurement-summary.json); функциональная таблица не заменяет их. Исторические результаты этапа 08 не считаются новыми результатами этапа 09. Физические low-RAM устройства, battery/thermal, x86_64 16 KiB и новый полный VPS rehearsal этими результатами не подтверждены.
