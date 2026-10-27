# MeetSpace Android — память и lifecycle, 27.10.2026

## Владение ресурсами

Activity ViewModelStore владеет Foundation/Meetings/Messaging/Recordings/DirectCall/CallDiscussion/SSH/Installer ViewModels. Compose читает состояние через collectAsStateWithLifecycle; foreground jobs привязаны к STARTED, DisposableEffect отсоединяет callbacks. Account/origin/generation guards отменяют и не публикуют устаревшие результаты. Application владеет единичным CallEngine/PushController: их process-lifetime scopes не считаются Activity leak.

CallEngine владеет PeerConnection/Factory, EGL, JavaAudioDeviceModule, local tracks/sources/capturers/SurfaceTextureHelpers, signaling и таймерами. VideoFeed владеет подключением sinks, renderer — своим SurfaceViewRenderer. Receiver track освобождает WebRTC owner, заимствованный VideoFrame не сохраняется. ScreenShareService — nonsticky projection FGS; rejected start/stop очищают resources независимо. Cleanup при slow control request исправлен MEDIA-001; idle projection при pause mic/camera должна сохраняться.

Recordings VM владеет playback lease; ExoPlayer останавливается/освобождается при disposal, thumbnail loader закрывается при STOP/account/disposal. Кеш8MiB и2decode jobs ограничены. Messaging history ограничена10×200 messages, notifications/conversations300, event IDs512; очереди имеют bounds. При этом ограниченные крупные attachment buffers/WSS очереди могут давать пики; это не доказанная retained leak.

SSH sessions/channels и secret byte arrays закрываются в finally, IO на отдельных workers. Отмена экспорта теперь очищает SAF документ даже до старта coroutine. Потоковый backup устраняет пропорциональную dump-size аллокацию; подробности замера в baseline-data-findings.md.

## Что реально измерено

Отдельный AVD API36 ARM64,4KiB pages, RAM4GiB, SwiftShader,2560×1600/density320, debug без LeakCanary. AuditOfflineLifecycleTest допускает только эмулятор без persisted profiles/session/fixture inputs и записывает numeric JSON. Проверяет20 Activity cycles (каждый: background/foreground + recreate + close),3 warmup;100 factory/EGL/audio-module create/dispose cycles,10 warmup. Все8 VM, Activities и decor views отслеживаются WeakReference после GC. Native factory workload не создаёт PeerConnection/track/capture и не заменяет проверку звонка.

Исходные наблюдения: Activity warmup Java5,490,128B→settled5,506,416B; native18,825,200B→13,283,248B; FD120→118; threads31→33;0/276 watched objects retained. Native100cycles: Java5,531,264B→5,547,648B; native18,096,256B→18,096,192B; FD122→122; threads33→33;0/330 wrappers retained. Небольшой Java рост включает сам измерительный список weak references/JSON, native/FD не растут. Повтор после lifecycle/permission исправлений выполнен на том же4KiB AVD; цифры ниже. Во время повтора параллельно работали второй AVD и локальные fixtures: это ограничивает причинное сравнение CPU/времени.

Нулевые weak references доказывают collectability наблюдавшихся Java объектов, не отсутствие всех C++ утечек. `summary.graphics=0` в SwiftShader accounting означает отсутствие отдельно учтённой категории, а не нулевое использование GPU. Heap/CPU/FD/thread измерения и JUnit статус должны интерпретироваться вместе; `status=completed` означает сбор данных, отдельный collectionAssertionPassed — результат проверки.

Отдельные живые recording/auth/media workloads, LeakCanary и ограничения перечислены в test report. Сырые heaps/traces/test credentials остаются в игнорируемой локальной папке и не публикуются. Android Studio GUI Memory Profiler не запускался. Полное отсутствие утечек на всём парке устройств не утверждается.

## Повтор после исправлений

| Workload | Java heap после warmup → settled | Native allocated | PSS KiB | FD / threads | Retained |
| --- | --- | --- | --- | --- | --- |
| 20 Activity cycles | 5494224 → 5506480 B | 18857440 → 18798096 B | 226161 → 226760 | 120 → 120 / 33 → 34 | 0/276 |
| 100 native factory cycles | 5527184 → 5543568 B | 18885552 → 18101472 B | 231306 → 231094 | 123 → 122 / 34 → 34 | 0/330 |

Обе collection assertions прошли; UID RX/TX не увеличились. Промежуточные PSS/FD измерения не показывают устойчивого линейного роста по циклам. CPU Activity workload вырос с5582ms до14620ms, wall с20307ms до42886ms; это измеренный результат, улучшение CPU не заявляется. Нагрузки хоста/компиляция/JIT не были изолированы, поэтому нужен отдельный controlled benchmark, прежде чем приписывать разницу коду. Native factory CPU1055→1106ms, wall12908→13072ms.

**LeakCanary2.14:** отдельная debug opt-in сборка на API36 ARM6416KiB AVD, 20 Activity +100factory циклов;2/2 tests PASS, явный `LeakAssertions.assertNoLeaks` для Activity workload прошёл. Это другое окружение (1080×1920/density420, RAM2GiB), его heap не сравнивается с4KiB baseline. Нормальные debug/release артефакты пересобраны без LeakCanary. Сырые numeric evidence лежат в `.audit/android-audit-20261027/{baseline,after,leakcanary}-{activity,native-factory}.json`; безопасные копии включены в evidence.

## Финальный повтор без параллельной сборки/UI

После APP-006 измерение повторено на том же4KiB AVD без запущенных Gradle/других UI tests (история JIT/cache не обнулялась). Источники: `afterquiet-activity.json`, `afterquiet-native-factory.json`, `final-quiet-lifecycle.json`; обе проверки PASS.

| Workload | Java B | Native B | PSS KiB | FD / threads | Retained |
| --- | --- | --- | --- | --- | --- |
| 20 Activity cycles | 5481936 → 5494128 | 18839792 → 13281408 | 225482 → 222637 | 120 → 118 / 32 → 33 | 0/276 |
| 100 factory cycles | 5514896 → 5535376 | 18093552 → 18093104 | 231141 → 231101 | 122 → 122 / 33 → 33 | 0/330 |

Activity CPU6359ms/wall22002ms; factoryCPU1003ms/wall12799ms. UID RX/TX прирост0. Это подтверждает собираемость наблюдавшихся объектов и отсутствие устойчивого накопления в данном workload; небольшой рост Java включает измерительные weak references. Предыдущий concurrent результат сохранён, но для основного сопоставления используется этот повтор. Полные числовые сравнения и ограничения — в performance report.
