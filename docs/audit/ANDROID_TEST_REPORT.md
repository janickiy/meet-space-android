# MeetSpace Android — проверки аудита, 27.10.2026

Это журнал подтверждённых результатов и оставшихся ограничений. Исходное состояние — HEAD `bb806e0b75ec720a695347b27d5d22d98a15b9a8` плюс 37 сохранённых изменённых/новых файлов предыдущих задач; оно зафиксировано до audit remediation. Подробности окружения и freeze — [baseline](ANDROID_AUDIT_BASELINE.md). Отчёт сохраняет исходные неуспешные прогоны и отдельно фиксирует результаты исправлений; ограничения не объявлены успешными проверками.

Все `.audit/…` пути ниже относятся к локальным игнорируемым доказательствам. Приватные fixtures, credentials, signed URLs, messages и heap dumps не включаются в публичные отчёты. Production серверы и Amnezia/VPN в аудитном тестировании не изменялись.

Имена evidence без префикса в таблицах разрешаются относительно
`/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/`.
Итоговый build — final2. Quiet lifecycle/startup, адресные UI повторы и runtime smoke final2 release завершены; доказательства и границы ниже.

## Идентичность проверяемых артефактов

| Артефакт | SHA-256 / статус |
| --- | --- |
| Frozen baseline debug | `160875cb5ee4c54c45214f351690f65a785ad2cf93c34f13d11a54bc74ba5aef` |
| Исправленный debug, normal build | `9988c9b8e92d790f3cd3b41529b4652ec666ca00c464060985fcf67cd3c90a1b` |
| Исправленный minified release APK | `a8dd5869453223d2396c45c30af6d4cc663f1a0cc1e94e7efe2b3556cf8f7f44`, unsigned |
| Исправленный release AAB | `08f56cf2b2d58a6079cd777a6c8c9d2d432df42467796e01a943f0888591f6c7`, unsigned |

Текущие хэши получены в `final2-artifact-verification.json` после APP-006; debug/APK/AAB имеют versionName `0.3.11-beta`, versionCode `29`. APK release и AAB остаются unsigned.

Baseline APK сохранён отдельно в `.audit/android-audit-20261027/baseline-apks/`; новые regression test APK запускались также на этом frozen приложении, чтобы подтвердить исходный дефект. Опциональная LeakCanary-сборка и временная тестовая подпись release для loader smoke не являются публикуемым обновлением и не подменяют normal build hashes.

## Сводка воспроизводимых проверок

| Проверка | Исходный результат | После исправлений / текущий результат | Доказательство |
| --- | --- | --- | --- |
| JVM + lint + debug/release APK/AAB | BUILD SUCCESSFUL, 1m46s | BUILD SUCCESSFUL, 2m20s | `baseline-gradle.log`, `final2-gradle.log` |
| JVM app | 238 PASS | 258 PASS | `baseline-test-summary.json`, `final2-test-summary.json` |
| JVM core:data | 170 PASS, 12 SKIP | 170 PASS, 12 SKIP | Те же summary; opt-in live checks отдельно ниже |
| JVM core:media | 28 PASS | 28 PASS | Те же summary |
| JVM core:ssh | 71 PASS | 71 PASS | Те же summary |
| JVM суммарно | 507 PASS, 12 SKIP, 0 failures/errors | 527 PASS, 12 SKIP, 0 failures/errors | Без удвоения отдельно запущенных live suite |
| Lint debug / release | 0 errors, 57 / 56 warnings | 0 errors, 57 / 56 warnings | Lint XML/HTML и summary; warnings не объявлены устранёнными |
| Installer Python | 126 PASS | 134 PASS | `baseline-installer-tests.log`, `final-installer-tests.log` |
| Stage09 verifier / initialization | 16 PASS | 16 PASS | `baseline-verifier-tests.log`, `final-verifier-tests.log` |
| Static debug/release/AAB verifier | PASS с RELRO observations | PASS с RELRO observations | `baseline-artifact-verification.json`, `final2-artifact-verification.json` |
| Полный offline UI/native набор, 16KiB AVD | 174 PASS, 6 FAIL, 16 SKIP из 196 на baseline | 184 PASS, 3 FAIL, 9 SKIP из 196; три ошибки пока открыты | `baseline-instrumentation.json`, `final-offline-ui.json`; разбор ниже |
| Offline lifecycle / native factory | 2 PASS | Normal debug на 4KiB AVD: 2 PASS; LeakCanary-вариант на другом 16KiB AVD: 2 PASS | `final-offline-lifecycle.json`, `final-leakcanary.json` |
| Priority pause/leave regressions | 2 FAIL на frozen APK | В финальном целевом наборе13/13 PASS | `baseline-media-priority.log`, `final-media-priority.json` |
| Priority screen stop regression | 1 FAIL на frozen APK | Входит в13/13 PASS | `baseline-screen-stop.json`, `final-media-priority.json` |
| Synthetic native screen FPS | Native source/codec check PASS | Около15FPS из реального framesEncoded при input≈144FPS | `native-screen-fps.json` |
| Actual MediaProjection / rotate / stop / restart | Первая попытка FAIL из-за orientation fixture | 1/1 PASS, первая сессия31.151с | `after-screen-fps-projection.json`, `final-screen-projection*.json` |
| ARM64 16KiB native loader | — | 1/1 PASS, compat mode отключён | `device-16k.json`, `after-native-16k.json` |
| Minified release 16KiB cold startup | — | PASS для итогового final2 APK: process и first-run UI видны | `final2-release16k-smoke.json`, `final2-artifact-verification.json` |
| Live repository/API/WSS, legacy fixture | Первые запуски включали opt-in skips | 9/9 PASS | `final-live-data.json` |
| Live recordings UI + actual SAF | 0 PASS, 4 FAIL старого harness | 4/4 PASS на current fixture08 snapshot | `baseline-live-recordings.json`, `final-live-recordings.json` |
| Live messaging UI | Первый прогон 0 PASS / 4 FAIL; промежуточный 1 PASS / 3 FAIL | После APP-006: 4/4 PASS | `after-live-messaging.json`, `final-live-messaging.json`, `final2-live-messaging.json` |
| Live messaging actual SAF | — | 1/1 PASS, SHA-256 исходного/экспортированного файла совпал | `final-live-messaging-saf.json` |
| Real Pion SFU / TURN / lifecycle | Readiness sample без RTP не являлся E2E | Первый набор 2 PASS / 1 FAIL; refreshed native call 1/1 PASS | `final-live-call.json`, `final-live-call-refreshed.json` |

Имена `final-*` обозначают сохранённый запуск, а не автоматическое закрытие всего аудита. В частности, наличие успешной сборки не доказывает FPS на устройстве, безопасную работу всех permission callbacks, полный SAF export или запись через SFU.

## Подтверждение исходных ошибок и адресные регрессии

### MEDIA-001: lifecycle cleanup за сетевым ожиданием, P1

На frozen baseline APK два новых теста не дождались освобождения за3с: pause при медленном conference lookup и leave при медленном presence. Дополнительный baseline screen-stop также не прошёл, когда normal command держал actor. Контрольная задержка освобождалась тестом после проверки; реальные звонки для воспроизведения не требовались.

Финальный `CallLifecyclePriorityTest` прошёл все8 сценариев:

- pause во время медленного lookup освобождает camera до завершения запроса;
- leave прерывает медленный presence, повторные lifecycle запросы сохраняют работоспособность actor;
- screen stop прерывает presence и освобождает projection owner;
- leave queued direct preview не открывает его и не отменяет следующий call;
- device-only pause сохраняет уже работающую idle screen share;
- callback старой projection не прерывает новую сессию той же встречи;
- завершение текущего direct call сохраняет более новый queued preview;
- stop queued screen start не расходует устаревшее consent и не включает capture позже.

Дополнительно `ScreenShareLifecycleTest`2/2 подтверждает cleanup после отклонённого consent и защиту нового direct call от старого cleanup/consent. Используются управляемые control-plane gates, fake capturer / логический screen owner и отдельные real-native resource paths; это не замена полному звонку на физическом устройстве.

### APP-005: поздние permission callbacks, P1

Пять JVM-тестов одноразового target guard входят в 258 app unit tests. Три actual ActivityResult/lifecycle instrumentation регрессии прошли в `final-media-priority.json`: permission после ON_STOP не включает mic после resume; обычный PAUSED системный диалог остаётся positive control и позволяет запрос; результат от прежней встречи не открывает camera в новой. Контекст включает account/call/participant/phase и состояние foreground. Системное согласие MediaProjection отдельно проверено actual projection тестом.

### APP-006: необязательный ресурс ошибочно отзывал доступ ко всему чату, P1

Реальный запуск на stage07 backend `6ecd…` показал: detail/history уже загружены,
затем отсутствующий в этой версии `/conversations/:id/pins` возвращал 404 и
`accessDenied` закрывал доступный чат. Это продуктовая ошибка совместимости, а не
отсутствующий `message-draft` tag. Теперь pins/search/context, pin mutation и
attachment upload/download 403/404 очищают собственную проекцию/ошибку и проверяют
canonical conversation. Только подтверждённый canonical detail/main history
403/404 очищает private cache; transient ошибки сохраняют загруженные данные.

Четыре новые regression methods входят в 48 `MessagingViewModelTest` и 258 app
unit tests: initial-open pins404, шесть типов ресурсов с canonical200,
canonical403/404 cleanup, сохранение cache при transient failures. Реальный
повтор `final2-live-messaging.json` прошёл 4/4: direct send/reply/edit/delete и
rotation, group/folders/MinIO files, foreground WSS/system notification/privacy,
offline reconnect и logout cleanup. Отдельный `final-live-messaging-saf.json`
прошёл actual OpenDocument/CreateDocument и проверку SHA-256 без fake provider.

### Остальные адресные исправления

| Проблема | Покрытие |
| --- | --- |
| APP-001: отмена SAF export до старта LAZY job оставляла документ | JVM cleanup до старта и при очистке VM; успешный экспорт сохраняется. Реальные OpenDocument/CreateDocument, upload/download и SHA-256: `final-live-messaging-saf.json` 1/1 PASS; recording CreateDocument входит в `final-live-recordings.json` |
| APP-002: отозванный доступ сохранял private cached meeting details/history | JVM detail/self/history regressions проверяют очистку после403/404 и сохранение при network error |
| APP-003: individual_tracks материалы не открывались без playable file | JVM проверка открытия доступных материалов без autoplay |
| APP-004: ring volume не восстанавливался после подключения headset | Три JVM регрессии разделяют speaker-only boost и разрешение восстановления |
| DATA-001: backup dump целиком аллоцировался в Python heap | Восемь focused tests нового/повторного backup, corrupt/restore/dump failure, файлового stdin и bounded heap; полный installer suite134 PASS |

До/после доказательства и ограничения памяти находятся в [performance report](ANDROID_PERFORMANCE_REPORT.md), [memory audit](ANDROID_MEMORY_LEAK_AUDIT.md), [data findings](baseline-data-findings.md) и [security audit](ANDROID_SECURITY_AUDIT.md).

## Native FPS, поворот и владение ресурсами

Synthetic `ScreenShareFrameRateTest` использует фактически упакованный WebRTC, software VP8 encoder/decoder и RTP stats. Без лимита получено143.934FPS; source adaptation даёт14.997FPS; после resize14.995, new source/track14.993, new peer14.996. После stop прирост framesEncoded равен0. Это проверка реального кодирования, а не только переданного значения15, но input144FPS создан синтетически.

Actual `ScreenShareProjectionTest` прошёл standard system consent, production foreground service / CallEngine capture, два поворота, stop и новый consent/restart. Capture/decode sizes450×720 и1152×720 совпадают. Первая session31.151с на экране AVD≈60Гц. Пять рабочих окон framesEncoded дают14.989,15.375,14.544,13.751,15.383FPS; два stop окна дают0. Значение maxFramerate15 применено к sender, но короткие окна около переходов не имеют строгого потолка15. Этот тест использует local loopback и тестовый SDP setup, без реального backend reconnect и server recording/segment boundaries.

Native loader smoke отдельно загрузил 3 библиотеки — `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libjingle_peerconnection_so.so` — и создал/освободил WebRTC factory на ARM64 API36 с16KiB pages и отключённым app compat. Предыдущий minified release запущен с временной тестовой подписью, first-run UI виден. Для текущего final2 release повторён runtime smoke: `final2-release16k-smoke.json` подтверждает startup/первый экран, page size16384 и disabled compat. Временная подпись относится только к тестовому APK. Вызовов, capture и live network в release smoke нет; другие ABI/OEM этим не проверены.

## Живые контракты на одноразовых сервисах

Использованы отдельные локальные disposable backend snapshots с настоящими
Gin/PostgreSQL/Redis/MinIO и валидированным HTTPS/WSS. Stage05/06/07 и legacy
stage08 закреплены на `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. Current stage08
собран из HEAD `f5267dcd025d21917512633ee92289531d90d18c` плюс 9 ранее существовавших
файлов recording-library overlay; идентичность, SHA-256 и адаптации test assembly
записаны в `fixture08/current-backend-version.json`. Это точный локальный snapshot,
не утверждение о текущем upstream/production. Business mocks не используются;
production-сервисы в этих проверках не менялись.

| Fixture | Контрактные проверки | Границы |
| --- | ---: | --- |
| Stage05, legacy | 60 PASS | Реальные auth/meeting API; без media pipeline |
| Stage07, legacy | 458 PASS в сохранённом JSON; 460 в терминале | Messaging/WSS/files/ACL. Последние 2 проверки выполняются после сериализации отчёта |
| Stage08, legacy | 758 PASS | Старый API, seeded recording/material artifacts; история результата сохранена отдельно |
| Stage08, current snapshot + overlay | 836 PASS | Включая global catalogue/pagination/ACL/active meetings; seeded artifacts, без capture pipeline/STT/AI |

Источники: `fixture05-contract-result.json`, `fixture07-contract-result.json`,
`fixture08/backend-contract-result-legacy.json`, `fixture08-current-contract-result.json`
и `fixture08/current-backend-version.json`. `final-live-data.json` отдельно
подтверждает 4 LiveRecordingsBackendTest, 3 LiveMessagingRepositoryTest,
2 LiveMeetingsRepositoryTest на legacy fixtures: 9 PASS, 0 SKIP/failures/errors.
Эти opt-in suite не прибавляются к unit total как новые уникальные тесты.

`final-live-recordings.json` подтверждает 4/4 реальных Android сценария уже на
current stage08 snapshot: Media3 playback/seek/rotation/cleanup, onboarding к
настоящему HTTPS серверу и недоступному SSH, pagination материалов/настроек,
actual CreateDocument export MP4 с проверкой SHA-256 и cleanup собственного файла.
Этот тест seeded MP4 не доказывает новый захват или серверную запись звонка.

Stage06 использует настоящий Pion SFU, Pion TURN и один echo peer. Readiness
sample с нулевыми RTP packets сам по себе не был доказательством медиасвязи.
Позднее `final-live-call.json` прошёл 2/3: cleanup уже открытого WSS при invalid
ICE и actual screen consent/background/stop. Native camera сценарий в этом
наборе дошёл до CONNECTED, но не дождался relay + decoded echo; этот FAIL сохранён.
После обновления disposable peer/conference inputs отдельный
`final-live-call-refreshed.json` прошёл 1/1: actual TURN relay, video decode echo,
ненулевые sent/received audio bytes, forced WSS reconnect с новым decode, cleanup
и rejoin. Emulator запущен без физического аудиовывода: RTP не означает проверку
слышимости или громкости. Это локальная двухсторонняя медиасвязь, не production
load/packet-loss/долгая server recording проверка.

## История ошибок harness и подтверждённые повторы

Первичный offline набор из 196 тестов дал 6 ошибок. Registration saved-state и
5 installer form tests использовали pointer clicks до стабилизации IME/layout:
часть координат была clipped/offscreen, callback не вызывался. После использования
существующего form-settling helper 22 registration/installer проверки прошли на
том же frozen APK. Это исправление теста не выдано за исправление продукта.
Первоначальный слишком строгий guard lifecycle probe тоже исправлен; отдельный
normal debug `final-offline-lifecycle.json` прошёл 2/2 на 4KiB AVD. LeakCanary
вариант независимо прошёл 2/2 на 16KiB AVD (`final-leakcanary.json`).

Полный исправленный UI/native набор на 16KiB AVD выполнен: `final-offline-ui.json`
дал **184 PASS, 3 FAIL, 9 SKIP из 196**. Первоначально не прошли:

- `MessagingUiTest#createGroupNeedsTitleAndSelectedUserAndWaitsForAcknowledgment`;
- `MessagingUiTest#composerRequiresContentAndBlocksDuplicateSendUntilServerReturns`;
- `RecordingsUiTest#librarySearchAndLargeTextKeepCardsReachableAndPrivateQueryIsNotRestored`.

Подтверждены ошибки harness: IME/layout не стабилизировались, а controlled draft callback не обновлял state. После исправления три теста прошли на frozen baseline APK (baseline-three-ui-harness-repaired2:3/3). Промежуточная версия helper дала ещё2 ошибки самого harness (fixed footer не имеет scroll ancestor; Dialog требует собственного keyboard dismiss) — они сохранены и исправлены optional scroll=false/действительным Espresso.closeSoftKeyboard.

Повтор целиком MessagingUiTest/RecordingsUiTest + system ringtone на итоговом APK дал47 PASS/1 FAIL/1 SKIP из49. Новая ошибка paginationAndServerSearchAreExplicitCallbacks имела ту же неверную controlled-query модель; исправлен только harness. Финальный адресный повтор всех4 исправленных случаев —4/4 PASS (`final-ui-harness-four.json`). Восстановление настоящего STREAM_RING отдельно прошло в49-test запуске. Итог по последнему результату каждого из исходных196 случаев:188 PASS/8 SKIP/0 остающихся failures, **это агрегат нескольких прогонов, не один зелёный полный запуск** (`evidence/offline-ui-reconciled.json`). Остаются5 screenshot-only opt-ins и3 hardware-probe opt-ins, не выданные за выполненные.

Первый live recordings UI запуск 4/4 FAIL ожидал удалённый tag
`recordings-choose-meeting` (3 случая) и завершился onboarding timeout. Обновлённый
harness сохраняет реальные playback/SAF/hash assertions; новый current-fixture
прогон 4/4 PASS описан выше. Первый live messaging прогон дал 4 FAIL, промежуточный
`final-live-messaging.json` — 1 PASS/3 FAIL. Проверка обнаружила реальный APP-006:
pins404 очищал выбранный чат. После его исправления и обновления ожиданий общего
notification center `final2-live-messaging.json` прошёл 4/4. Исходные и
промежуточные неуспешные логи оставлены для воспроизводимости.

Первоначальный actual projection FAIL был `Test activity did not change orientation`, а не превышение измеренного FPS. После исправления orientation fixture финальный actual projection1/1 PASS. Промежуточный compile failure в `after-core-gradle.log` также не скрывается: следующий `after-core-rebuild.log` и итоговый `final-gradle.log` и `final2-gradle.log` успешно завершают сборку.

## Команды повторения

Сборка и unit/lint выполняются из
`/Users/aleksandranickij/htdocs/Android` с JDK17; секретные live параметры через
командную строку не передаются. Установленные APK и приватные fixture inputs
готовятся отдельно на owned AVD. Команды не выполняются самим чтением отчёта;
перед повтором следует выбрать новый `--label`, сохранив исходные evidence.

```sh
./gradlew testDebugUnitTest :core:model:test lintDebug lintRelease \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:assembleRelease :app:bundleRelease \
  :app:dependencies --configuration releaseRuntimeClasspath --console=plain
python3 -m unittest discover -s installer/tests -p 'test_*.py' -v
python3 -m unittest discover -s testing/stage09 -p 'test_*.py' -v
python3 testing/stage09/verify_artifacts.py --version 0.3.11-beta \
  --version-code 29 --generate-smoke-apk \
  --report .audit/android-audit-20261027/final2-artifact-verification.json
```

Audit instrumentation wrapper проверяет отдельный owned AVD, распознаёт runner
status и сохраняет `<label>.log`/`<label>.json` в указанном audit home; adb exit0
сам по себе не считается PASS. Наборы с подтверждёнными результатами:

```sh
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-media-priority --serial emulator-5556 \
  --classes CallLifecyclePriorityTest,ScreenShareLifecycleTest,CallPermissionLifecycleTest
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final2-live-messaging --serial emulator-5556 --classes LiveMessagingTest
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-live-messaging-saf --serial emulator-5556 --classes LiveMessagingSafTest
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-live-recordings --serial emulator-5556 --classes LiveRecordingsTest
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-screen-projection --serial emulator-5556 --classes ScreenShareProjectionTest
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-live-call --serial emulator-5556 --arg quality09=true \
  --classes 'CallSignalingLifecycleTest#rejectedIceClosesAlreadyOpenedSignaling,LiveCallTest#screenConsentBackgroundAndStop,LiveCallTest#nativeCameraRelayReconnectAndCleanup'
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-live-call-refreshed --serial emulator-5556 \
  --classes 'LiveCallTest#nativeCameraRelayReconnectAndCleanup'
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-offline-lifecycle --serial emulator-5556 \
  --classes AuditOfflineLifecycleTest --arg auditOffline=true --arg auditLabel=afterquiet
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-leakcanary --serial emulator-5558 \
  --classes AuditOfflineLifecycleTest --arg auditOffline=true \
  --arg auditLeakCanary=true --arg auditLabel=leakcanary
python3 .audit/android-audit-20261027/run_instrumentation.py \
  --label final-offline-ui --serial emulator-5558 \
  --classes FoundationUiTest,RegistrationUiTest,MeetingsUiTest,CallUiTest,MessagingUiTest,RecordingsUiTest,SettingsUiTest,InstallerUiTest,SshWizardUiTest,PersonalCallUiTest,AccountMediaPanelsTest,NotificationCenterUiTest,MessagingNotificationsTest,RecordingPreviewTest,IncomingCallRingtoneTest,ScreenShareLifecycleTest,NativeLibrariesSmokeTest,MicrophoneProcessingNativeTest
```

Offline probe требует пустое app storage без persisted accounts/sessions, live fixture inputs и push consent. Его нельзя направлять на пользовательское устройство или очищать чужие данные ради запуска. LeakCanary вариант собирается отдельно с `-PmeetspaceLeakCanary=true` и запускается с `auditLeakCanary=true`; normal release этого debug dependency не содержит. Live fixtures требуют приватных настроек и изоляции, описанных в `testing/stage05`–`stage08`; значения не входят в этот отчёт.

Stage06 refreshed peer запускался через
`GOTOOLCHAIN=go1.26.10 python3 testing/stage06/run_peers.py --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture06 --count 1`.
После этого новые private properties и fixture CA копируются в app-private inputs
по stage06 README; старый marker другого stage не должен выбирать другой backend.

Для opt-in JVM suite нужны соответствующие запущенные disposable assemblies и
приватные properties. В частности, evidence 9/9 относится к legacy stage08;
файл `live-legacy.properties` нельзя считать доказательством, что сервис по
указанному адресу всё ещё имеет legacy identity после перехода на current snapshot.

```sh
MEETSPACE_STAGE05_LIVE=1 \
MEETSPACE_STAGE05_FIXTURE=/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture05/live.properties \
MEETSPACE_STAGE06_LIVE=1 \
MEETSPACE_STAGE06_FIXTURE=/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture06/live.properties \
MEETSPACE_STAGE07_LIVE=1 \
MEETSPACE_STAGE07_FIXTURE=/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture07/live.properties \
MEETSPACE_STAGE08_LIVE=1 \
MEETSPACE_STAGE08_FIXTURE=/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture08/live-legacy.properties \
./gradlew :core:data:testDebugUnitTest \
  --tests com.meetspace.core.data.LiveRecordingsBackendTest \
  --tests com.meetspace.core.data.LiveMessagingRepositoryTest \
  --tests com.meetspace.core.data.LiveMeetingsRepositoryTest --rerun-tasks --console=plain

MEETSPACE_STAGE05_HOME=/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture05 \
python3 testing/stage05/backend_contract_check.py
GOTOOLCHAIN=go1.26.10 python3 testing/stage07/run_contract.py \
  --home /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture07
```

Current stage08 контракт повторяется из рабочего каталога
`/Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture08/backend`:

```sh
GOTOOLCHAIN=go1.26.10 go run ./cmd/android-stage08-contract \
  /Users/aleksandranickij/htdocs/Android/.audit/android-audit-20261027/fixture08
```

Используется сохранённый current probe из identity manifest. Старый
`testing/stage08/run_contract.py` копирует legacy contract поверх этого probe;
такой запуск не воспроизводит именно current 836-check результат.

## Оставшиеся ограничения проверки

- UI: полная история прогонов выше;188 PASS/8 SKIP по последним результатам случаев, без нового единого196-test запуска после правок harness. Live messaging4/4, SAF1/1 и recordings4/4 подтверждены отдельно. Последний final2 minified release startup smoke прошёл на16KiB.
- Финальный quiet lifecycle2/2 на исходном4KiB AVD прошёл; retained0/276 Activity/VM и0/330 factory wrappers. Cold p50/p95:1639/2020→960/1098ms,n7; after warm128/150ms,n7. Нельзя приписать разницу оптимизации: история JIT/cache/нагрузки различалась; baseline warm не измерен. LeakCanary16KiB — отдельный sample, см. performance report.
- Нет физического144Гц устройства, Bluetooth/earpiece/headset/USB audio matrix, реального измерения громкости/микрофона и OEM background/Doze push delivery. FCM отключён без google-services.json; полный FCM E2E не проверен.
- Подтверждён локальный настоящий Pion/TURN relay с echo peer, принудительным reconnect и rejoin; это не проверка production reconnect при смене Wi-Fi/mobile, многопользовательской нагрузки под потерями/задержками или OEM background capture. Долгий server recording с несколькими segment boundaries и STT/AI pipeline не проверены.
- Нет Play pre-launch report, постоянной release подписи, проверки совместимого обновления опубликованного APK, публикации, теста всех ABI/Android26–36/OEM и полного hardware performance/battery профиля.
- OSV exact-version scan178 Maven coordinates не нашёл advisory matches на момент запроса; это не полная проверка native C++/WebRTC/toolchain vulnerabilities и не гарантия отсутствия уязвимостей. Безопасность и остаточные риски описаны отдельно.

## Завершение стендов

Одноразовые fixture05–08 остановлены, контейнеры/базы/приватные homes удалены; все проверенные их localhost ports закрыты. Production не менялся. Безопасные JSON evidence скопированы в `docs/audit/evidence`; raw журналы и snapshots APK остаются в игнорируемой `.audit/android-audit-20261027`. Для повторения fixtures создаются заново с новыми credentials, а не переиспользуют удалённые данные.

Точные current fixture08 Go assembly/probe сохранены отдельно после удаления private homes: `.audit/android-audit-20261027/current-fixture-replay/cmd/android-stage08-{fixture,contract}/main.go`. Их SHA-256 совпадают с исходным `fixture08-current-backend-version.json`; `restoration-verification.json` подтверждает идентичность без повторной сборки/теста. При повторении нужны HEAD snapshot и9 overlay files с указанными SHA, новые configs/credentials и новый disposable PostgreSQL; runtime configs не сохраняются в публичном отчёте.

Последние изменения тестового harness собраны и проверены lintDebug (`final-ui-harness-build3.log`, SUCCESS). Полная производственная сборка/юнит/lintDebug/lintRelease остаётся final2; после неё изменены только androidTest и отчёты.
