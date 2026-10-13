# Stage 09: lifecycle и измерение памяти

Дата: 2026-10-11. Это статический аудит и описание воспроизводимых измерений. Итоговые численные результаты baseline/after и общий статус QA ведутся в [FINAL_QUALITY_AUDIT.md](FINAL_QUALITY_AUDIT.md). Отсутствие находки при чтении кода не означает отсутствие утечек на всех устройствах.

## Подтверждённый дефект и узкое исправление

В `core/media/.../CallEngine.kt`, метод `open()`, новый `CallSignaling` присваивался полю `signaling` только после `CallProtocol.ice(session.iceConfiguration)`. При несовместимой или уже истекающей ICE-конфигурации проверка выбрасывает исключение. Общий путь `fail()` → `disposeAll()` → `closeNetwork()` не видел созданный ресурс и не вызывал его `close()`.

Production repository хранит последний signaling и закрывает его при следующем `openCall` либо смене авторизации. Поэтому подтверждён оставшийся WebSocket после отказа подключения; не заявляется неограниченный рост числа сокетов. Обычный выход из failed-состояния этот ресурс в исходной реализации не закрывал.

Baseline воспроизведён на сохранённом APK до исправления: `CallSignalingLifecycleTest.rejectedIceClosesAlreadyOpenedSignaling`, лог `.audit/stage09/baseline-ice-regression.log`. Тест завершился ожидаемой ошибкой `expected:<1> but was:<0>` для числа вызовов `close()`, предварительное утверждение о создании одного signaling прошло. Счётчик создания означает вызов реального OkHttp `newWebSocket`, а не подтверждение завершённого handshake. Используются HTTPS/WSS disposable stage06 fixture и её CA; подменяется только ICE-документ, возвращаемый тестовым backend. `finally` отменяет baseline-сокет после проверки.

Исправление после воспроизведения: передать ресурс полю `signaling` сразу после `repository.openCall`, до разбора ICE. Существующий общий cleanup теперь владеет ресурсом и при ошибке проверки. Повторный прогон на исправленном APK завершился `OK (1 test)`; лог `.audit/stage09/after-ice-regression.log`. Закрытие созданного signaling при отказе ICE подтверждено.

## Проверенные владельцы и пути освобождения

| Область | Что проверено в фактическом коде | Статический результат |
| --- | --- | --- |
| `MainActivity`, VM factories | Application repositories передаются VM; Activity lifecycle управляет UI collectors | Постоянного захвата Activity в application singleton/VM factory не обнаружено |
| Foundation/Meetings/Messaging/Recordings VM | `viewModelScope`, отмена работ, смена identity, поколения запросов | Коллекторы отменяются с VM; устаревшие результаты ограничены scope/ticket checks |
| Messaging realtime | `ownedSession` закрывается в `finally`, event/reconcile jobs отменяются, notification listener удаляется | Конкретная незакрытая подписка не обнаружена |
| Media3 | `ExoPlayer` получает application context; `DisposableEffect` снимает listener и lifecycle observer; `PlayerView.onRelease` снимает player | Baseline heap подтвердил duration timer, удерживающий released player; добавлен `stop` перед `release`; after weak refs и три HPROF подтверждают отсутствие прежнего player/timer path |
| Recording streams/export | `RecordingAccess` ограничен учётной записью, закрывается при смене scope/выходе; output stream в `use`; partial SAF cleanup асинхронный | Постоянная ссылка на Activity в cleanup не обнаружена; используется application resolver |
| CallEngine | Один application-scoped actor; bounded queue 256; generation guard; отмена reader/timers/stats/reconnect; dispose PC/factory/ADM/EGL | Один executor намеренно живёт весь процесс; повторное создание engines не является сценарием приложения |
| Camera/mic/screen | Track/source/helper/capturer cleanup; `VideoFeed` синхронно отсоединяет sinks; renderer освобождается при disposal | Конкретная дополнительная потеря владельца не обнаружена |
| ScreenShareService | Свежий consent, `START_NOT_STICKY`, stop/destroy/task-removed paths | Activity context не хранится сервисом/engine; callbacks проходят generation guard |
| SSH/installer VM | Отмена работы и polling, закрытие import stream, очистка credentials/key buffers при detach/clear | Аудит сетевых SSH реализаций и remote state относится к отдельному installer QA |

`RecordingFiles.cleanupScope` и `PushController.scope` также имеют lifetime процесса и получают application context. Само наличие живого scope не является доказательством утечки. FCM сейчас не настроен, поэтому runtime-проверка реального push этим аудитом не покрыта.

## Harness и условия сравнения

`QualityLifecycleTest` включается только с `-e quality09 true` и приватными fixture-файлами. Каждую функцию следует запускать в новом instrumentation-процессе. Для heap dump выделен отдельный AVD `emulator-5556`: API 36, 4 KiB pages; он не содержит пользовательских профилей из прежнего AVD. Запрошенные 1536 MiB эмулятор переопределил на 4096 MiB; фактически `/proc/MemTotal` = 4014920 kB, heap growth limit = 192 MiB, heap size = 576 MiB. Это не подтверждённое low-RAM устройство. Первичный источник условий — `docs/android/evidence/09/measurement-device.json`; фактический ABI/version/page size также сохраняются в JSON каждого запуска.

| Метод | Циклы по умолчанию | Что упражняется |
| --- | --- | --- |
| `nativeFactoryDisposalBatches` | 10 warmup + 100 measured | EGL + ADM + native factory, dispose/release; без peer и захвата |
| `realCallJoinLeaveBatches` | 10 warmup + 100 measured | Один реальный application engine, HTTPS/WSS/Pion/ICE/TURN, join/leave; camera/mic/screen выключены |
| `activityAndRecordingLifecycleBatches` | 3 warmup + 20 measured | Реальная навигация к записи, native MP4 frame, Activity recreation, закрытие player/route и уничтожение Activity |
| `authLoginLogoutBatches` | 3 warmup + 20 measured | Реальные HTTPS login/logout через Foundation VM; загрузка закрытых данных записи, очистка scoped state; weak refs старых session/recording DTO |

Call-тест держит одну foreground Activity для audio focus Android 15+; её наличие в heap ожидаемо. Echo fixture не создаёт 100 циклов encode/decode без исходящего захвата: media/camera/MediaProjection отдельно проверяются существующими LiveCall tests. Activity-тест требует stage08 recordings fixture; его snapshots снимаются после `ActivityScenario.close()`.

Auth-тест намеренно держит одну Activity и один реальный профиль. Fixture имеет один разрешённый emulator origin; второй фиктивный alias не добавлялся. Live server-switch этим методом не покрыт и не должен отмечаться пройденным.

Настройки: `qualityLabel` (по умолчанию `baseline`), `qualityWarmup`, `qualityCallCycles`, `qualityFactoryCycles`, `qualityActivityCycles`, `qualityAuthCycles`, `qualityHprof=true`. JSON: `cache/stage09-quality-<label>-<method>.json`. HPROF: тот же stem плюс `-warmup.hprof` и `-end.hprof`.

После прогрева и каждых 10 measured-циклов выполняются `waitForIdleSync`, три попытки GC/finalization с паузами 200 ms. Финальный settled snapshot дополнительно ждёт 5 s. Сохраняются Java used/committed heap, native heap, Dalvik/native/total PSS, RSS, process CPU time, UID RX/TX, ART allocation/GC counters, число threads/FDs, число живых weak refs. Для Activity-теста наблюдаются Activity/decor, Foundation/Meetings/Messaging/Recordings VM и Player/PlayerView. Для factory-теста — factory/ADM/EGL wrappers.

Это process-wide наблюдения. GC не гарантирует немедленный сбор; RSS/PSS и нативные allocator caches сами по себе не доказывают leak. Warmup/end HPROF снимаются после числовых snapshots и могут влиять на последующие измерения; baseline/after должны использовать одинаковые параметры. Retention требует анализа пути от GC root и сравнения нескольких batches после прогрева.

JSON не содержит URL, идентификаторы встреч/учётных записей, FD destinations, thread names или тексты исключений. HPROF не может быть достоверно обезличен этим harness: он может содержать fixture credentials, остаётся приватным и не коммитится/не публикуется.

## Разбор роста потоков в baseline звонка

В повторном baseline с 10 warmup + 100 measured join/leave и дополнительным idle 65 s число `/proc/self/task` threads составило 40 → 50 → 45; FD 139 → 139 → 136. Порядок: warmup → settled 5 s → ещё 65 s idle. Это фактический [baseline-real-call.json](evidence/09/baseline-real-call.json); [обезличенный HPROF-агрегат](evidence/09/baseline-call-thread-hprof.json) содержит SHA256 приватных heap dumps.

| HPROF показатель | Warmup | Settled 5 s | Ещё 65 s idle |
| --- | ---: | ---: | ---: |
| Thread GC roots | 38 | 48 | 43 |
| Android Binder roots | 4 | 15 | 15 |
| OkHttp roots | 6 | 5 | 2 |
| Coroutine dispatcher roots | 4 | 4 | 4 |
| MeetSpace actor roots | 1 | 1 | 1 |
| Okio watchdog roots | 1 | 1 | 0 |
| Остальные runtime/framework roots | 22 | 22 | 21 |
| Общий TaskRunner: workers | 5 | 4 | 2 |
| OkHttp Dispatcher: workers | 1 | 1 | 0 |
| ThreadPoolExecutor instances | 17 | 17 | 17 |
| TaskQueue instances | 7 | 7 | 6 |
| CallEngine / MainActivity instances | 1 / 1 | 1 / 1 | 1 / 1 |
| PeerConnection / PeerConnectionFactory / JavaAudioDeviceModule | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

Warmup→settled прирост Java roots объясняется +11 Binder и −1 OkHttp; после idle ушли ещё 3 OkHttp, 1 Okio watchdog и 1 framework root. Разница `/proc` и Java roots = 2 во всех трёх снимках; это разные множества наблюдений. Единственный `RealWebSocket` имеет `failed=true` в каждом heap; тестовый backend намеренно хранит `lastSocket`. Число PC/factory/ADM wrappers не растёт. Одна foreground Activity и application CallEngine намеренно остаются живыми.

Из самих heap прочитаны настройки общего TaskRunner: core = 0, max = Int.MAX_VALUE, keep-alive = 60 s; workers принадлежат одному общему пулу, включая временно переименованный worker reader. Ready/busy queue counts: 1/1 → 1/1 → 1/0. Освобождение свободных потоков после таймаута теперь подтверждено runtime и соответствует [OkHttp 4.12.0](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/concurrent/TaskRunner.kt). Это не утверждение, что все process-wide потоки должны исчезать после leave.

Binder roots достигли 15 и сохранились после idle. В [AOSP Android 16 ProcessState](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/libs/binder/ProcessState.cpp) исходный max Binder threads = 15; runtime override данного процесса не измеряется, поэтому универсальный предел устройства не заявляется. Наблюдаемые roots соответствуют прогретому системному пулу, а не новым CallEngine/PeerConnection. Специальный thread-анализ не вычислял retained-byte dominators.

Первый короткий прогон отличался: threads 39→49, Java roots 37→47, Binder 4→12 и OkHttp 5→7, без дополнительного idle. Его результаты сохранены отдельно в [baseline-short-real-call.json](evidence/09/baseline-short-real-call.json) и [baseline-short-call-thread-hprof.json](evidence/09/baseline-short-call-thread-hprof.json); они не смешиваются с повторным запуском. Первые raw call HPROFs были заменены повторным download; агрегат остаётся, независимая повторная обработка именно первых dumps недоступна.

`qualityIdleSettleMs=65000` добавляет **после прежнего** settled snapshot/end HPROF дополнительный `idle-settled` snapshot и idle HPROF для real-call или Activity/recording. Значение по умолчанию 0. Для сравнения времени/памяти параметры baseline/after должны совпадать; отдельная проверка истечения таймера не является заменой полной серии циклов.

## Подтверждённое удержание released player в Media3

После 3 warmup + 20 measured Activity/recording циклов baseline weak refs показывают 0 из 46 Activity/decor/PlayerView и 0 из 23 каждого из четырёх VM. После 5 s settling остаётся один последний ExoPlayer, предыдущие экземпляры собраны. Полный Shark 2.14 analysis и целевой поиск player paths сохранены в [09heap-analysis.json](evidence/09/09heap-analysis.json); raw labels/HPROF остаются приватными.

End dump содержит один `ExoPlayerImpl` с `playerReleased=true`; его internal player имеет `releasedOnApplicationThread=true`, enabled renderer count = 0. `MediaCodec`, `AudioTrack`, `PlayerView`, Activity и все четыре VM отсутствуют. Единственный player удерживает 29 031 Java bytes / 772 objects. Warmup содержит три уже released player по тому же пути, примерно по 29 KiB каждый. Эти размеры не измеряют native memory.

Путь от GC root: instrumentation thread → основная MessageQueue → queued Message → Handler callback → `StuckPlayerDetector.player` → released ExoPlayer. Стандартные Android heuristics Shark также сообщают одну application group: остановленную playback MessageQueue через этот player. Нельзя описывать такой результат как «0 leaks». Целевой player pass показывает удержание, но сам по себе не назначает каждому выбранному объекту статус утечки.

Queued message имеет `what=3` (duration timeout) и `when=1037209 ms` uptime; числовой settled snapshot `elapsedRealtime=1034964 ms`. Разница около 2.245 s предполагает скорое истечение для этой 10-секундной fixture, но часы имеют разные правила учёта deep sleep; фактический момент освобождения этим сравнением не доказан. Для длинной записи задержка может быть существенно больше.

Причина согласуется с точными исходниками Media3 1.11.1: [StuckPlayerDetector.release](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/util/StuckPlayerDetector.java#L105-L108) сначала удаляет сообщения, затем listener. [ListenerSet.remove/release](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/util/ListenerSet.java#L423-L428) может синхронно доставить оставшееся `onEvents`, заново планирующее таймер. В [ExoPlayerImpl.release](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java#L1097-L1114) переход состояния в IDLE происходит позже. Это объяснение выведено из совпадения heap path и порядка вызовов; отдельный инструментированный callback trace библиотеки не снимался.

Узкое исправление `RecordingPlayer`: сначала сохраняются позиция и `playWhenReady`, снимаются listener приложения и lifecycle observer, затем выполняется `stop()` и в `finally` — `release()`. STATE_IDLE до снятия detector listener предотвращает повторное планирование playback duration timeout. Pause/rotation должны по-прежнему восстанавливаться из сохранённых значений. Полная after Activity/recording серия выполнена: те же 3 warmup + 20 measured циклов, 46 watched player instances. Уже в warmup, промежуточных batches и после обычных 5 s settling weak refs player = 0. Отдельный pause/seek/rotation regression `LiveRecordingsTest.realVideoPlaysSeeksRotatesAndReleases` также прошёл на измеренной версии 0.9.0 в серии 4/4 LiveRecordings tests; привязка повторных прогонов к финальным артефактам ведётся в общем отчёте.

Фактические AAR из Gradle cache, SHA256:

- `androidx.media3:media3-common:1.11.1` — `50813cf219c7c93ed8a32a750891ca6d1280729ecf7a90c49567adacd67ef31b`.
- `androidx.media3:media3-exoplayer:1.11.1` — `588592baec110837c33ce0c82bb76464203e6b0621177bbe5d88779001a6f1ed`.

Для auth baseline (3 warmup + 20 measured) default Android Shark filters дали 0 application/library groups в обоих dumps; `AuthContext` и `AccountSession` после logout — 0. Одна Activity и каждый из четырёх VM остаются по дизайну измерения. Это проверка именно logout/login одного fixture-профиля, не доказательство server-switch или отсутствия любых утечек.

## Проверка heap после исправлений

[09heap-analysis.json](evidence/09/09heap-analysis.json) дополнен after warmup/end/idle анализом Activity/recording и warmup/end для auth/native factory. В каждом из трёх Activity dumps **0 ExoPlayerImpl, ExoPlayerImplInternal, StuckPlayerDetector, PlayerView, MediaCodec, AudioTrack, Activity и всех четырёх VM**; связанных detector messages также 0. Default Android Shark filters дают 0 application и 0 library groups. Конкретный путь удержания baseline исчез, что независимо согласуется с 46/46 собранными player weak refs. After idle 65 s — дополнительная проверка, которой нет в Activity baseline; численное сравнение делается по совпадающим warmup/settled 5 s снимкам.

Auth after сохраняет 0 `AuthContext`/`AccountSession` после logout и 0 heuristic groups; одна Activity и её четыре VM удерживаются самим сценарием намеренно. Native factory after: PC/factory/ADM/EGL wrapper counts = 0, heuristic groups = 0; все 110 weak refs каждой категории factory/ADM/EGL собраны. Эти выводы относятся к выбранным fixture-сценариям и объектам, а не ко всем возможным ресурсам приложения.

After звонок также прошёл 10 warmup + 100 measured join/leave. [After thread aggregate](evidence/09/after-call-thread-hprof.json):

| After HPROF показатель | Warmup | Settled 5 s | Ещё 65 s idle |
| --- | ---: | ---: | ---: |
| Thread GC roots | 39 | 49 | 44 |
| Binder roots | 5 | 16 | 16 |
| OkHttp roots | 5 | 5 | 2 |
| Coroutine roots | 5 | 4 | 4 |
| Actor roots | 1 | 1 | 1 |
| PC / factory / ADM | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

`/proc` threads = 41→51→46. После idle вновь завершились 3 OkHttp, 1 Okio и 1 runtime root; TaskRunner/Dispatcher освобождаются как в baseline. Binder на один root больше, чем в baseline, и не меняется после idle. Это различие прогретых process pools; лимит 15 из AOSP не трактуется как универсальная граница числа наблюдаемых Binder roots. CallEngine и foreground Activity по одному, единственный canceled WebSocket удерживает test backend. Новых per-call wrapper накоплений нет.

Не следует смешивать `nativeHeapAllocatedBytes` (актуально выделенные native bytes), `nativeHeapBytes` (ёмкость native allocator heap), RSS и PSS. В обоих запусках ёмкость увеличивается примерно на 41–45 MB после HPROF, при почти неизменных выделенных bytes: factory baseline 11.317→11.327 MB, after 11.317→11.327 MB; call baseline 19.513→19.716 MB, after 19.522→19.720 MB. Это не after-only регрессия и не доказательство native leak. Универсальная гарантия отсутствия утечек либо причинное объяснение всего RSS из этих данных не следуют.

## Отдельный opt-in LeakCanary

Обычная debug-сборка и release APK/AAB не включают LeakCanary. `-PmeetspaceLeakCanary=true` добавляет `leakcanary-android:2.14` только в debug и `leakcanary-android-instrumentation:2.14` в Android tests. Итоговая версия приложения повышена до `0.9.0` / code `8`; baseline бинарники сохранены отдельно до изменения версии.

После сборки и установки обоих opt-in APK параметр `-e qualityLeakCanary true` вызывает `LeakAssertions.assertNoLeaks("activity-recording")` по окончании Activity/recording measurement и fixture cleanup. Reflection позволяет обычному test APK компилироваться без LeakCanary. Если параметр передан без зависимости, проверка явно падает с инструкцией пересборки, а не сообщает ложный успех.

JUnit отключает автоматический asynchronous heap dump LeakCanary; instrumentation artifact выполняет явную синхронную проверку. Использован официальный [UI test workflow](https://square.github.io/leakcanary/ui-tests/) и проверена [сигнатура API v2.14](https://github.com/square/leakcanary/blob/v2.14/leakcanary-android-instrumentation/src/main/java/leakcanary/LeakAssertions.kt). Факт возврата assertion означает, что настроенный detector не сообщил application leak; это не универсальная гарантия отсутствия всех удерживаемых объектов. Отдельный файл `*-leakcanary.json` сохраняет статус; отчёты и heap остаются приватными.

LeakCanary изменяет наблюдаемую нагрузку и набор watchers; его запуск нельзя смешивать с численным сравнением baseline/after без этой зависимости. Android Studio здесь отсутствует; raw HPROF подготавливается для внешнего Shark анализа. CPU trace, jank, battery, реальные Bluetooth/гарнитуры, другие Android/ABI и low-memory kill не измеряются приведёнными тестами и требуют отдельных результатов в общем отчёте.

### Выполненный opt-in run

На отдельном API36 ARM64/4KiB AVD выполнены3 warmup+3 измеряемых цикла Activity/recording с LeakCanary2.14. Instrumentation завершилась `OK (1 test)`; `LeakAssertions.assertNoLeaks` вернулся без application leak assertion. [Статус detector](evidence/09/leakcanary-activity-recording-leakcanary.json), [отдельные метрики](evidence/09/leakcanary-activity-recording.json). Эти числа не входят в baseline/after comparison. Затем обычные debug/test APK пересобраны без свойства; все290 uncompressed entries обычного APK совпали с измеренным after APK, полный ZIP hash отличается ([проверка](evidence/09/normal-rebuild-equivalence.json)).
