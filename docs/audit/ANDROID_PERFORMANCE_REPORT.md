# MeetSpace Android — производительность, 27.10.2026

Отчёт фиксирует измеренные результаты текущего аудита. Исходная точка описана в [baseline](ANDROID_AUDIT_BASELINE.md): HEAD `bb806e0b75ec720a695347b27d5d22d98a15b9a8` вместе с сохранёнными изменениями предыдущих задач. Ниже отдельно указаны исходный APK, промежуточный повтор при параллельной нагрузке, финальный повтор после APP006 без новых сборочных/UI задач и дополнительное измерение в другом окружении. Исходные результаты сохранены; наблюдаемое различие не приписывается автоматически исправлениям приложения.

Числовые доказательства находятся в игнорируемой локальной папке `.audit/android-audit-20261027/`. В отчёт не включены учётные данные, URL подписанных файлов, содержимое пользовательских сообщений и heap dump.

## Окружение и метод

| Измерение | Окружение / границы |
| --- | --- |
| Baseline/after startup и lifecycle | Отдельный AVD `MeetSpace_Audit_20261027`, `emulator-5556`, Android API36 ARM64, 4KiB pages, RAM4GiB, 2560×1600 / density320, SwiftShader; debug без LeakCanary. Lifecycle повторён при параллельной нагрузке и затем без новых Gradle/других UI задач; история процесса/хоста и caches не сбрасывались до одинакового состояния |
| Native FPS / фактический MediaProjection | Тот же API36 ARM64 AVD; WebRTC `144.7559.15`, native VP8/libvpx, локальное WebRTC loopback-соединение |
| Дополнительный LeakCanary / 16KiB loader | Отдельный API36 ARM64 16KiB AVD, `emulator-5558`; другой профиль устройства, поэтому его память не сравнивается с исходным AVD как «до/после» |
| Хост сборки | macOS Darwin27 ARM64, RAM24GiB, JDK17.0.20.1, Gradle8.13, AGP8.13.2 |
| Физические устройства | Не использовались. Высокочастотный экран, аппаратный encoder, энергопотребление, температура и OEM audio routing не измерены |

## Запуск приложения

Источники: `baseline-startup.json`, `after-startup.json`. Измерялся `am start -W` после `am force-stop`, без очистки данных: в каждом прогоне два разогревочных запуска исключены, семь запусков вошли в выборку. Метрика `TotalTime` означает первое отображение Activity, а не полностью загруженный экран (`fully drawn`). Финальный cold sample снят с debug APK SHA-256 `9988c9b8e92d790f3cd3b41529b4652ec666ca00c464060985fcf67cd3c90a1b` после APP006; baseline SHA указан в исходном отчёте.

| Cold process start: TotalTime | Baseline | После APP006 |
| --- | ---: | ---: |
| Семь измерений, мс | 1598, 1639, 1654, 1553, 1504, 1665, 2020 | 984, 1068, 960, 928, 947, 888, 1098 |
| p50 / медиана | 1639 мс | 960 мс |
| Минимум / максимум | 1504 / 2020 мс | 888 / 1098 мс |
| p95 nearest-rank | 2020 мс | 1098 мс |

На том же API/AVD финальные значения ниже, но это не доказательство startup optimization: целевого изменения запуска не было, а JIT, code/data caches, накопленная история AVD и нагрузка хоста между сериями различались. Для причинного вывода нужен контролируемый benchmark. В каждой серии n=7; p95 рассчитан nearest-rank (седьмое значение после сортировки) и не является устойчивой оценкой хвоста.

Отдельно `after-warm-startup.json` измеряет создание новой Activity/task с `NEW_TASK|CLEAR_TASK` в уже работающем процессе. PID проверен неизменным, Android сообщает `LaunchState=WARM`; два warmup исключены. Семь `TotalTime`: 145, 138, 150, 128, 107, 121, 109 мс; p50=128 мс, p95 nearest-rank=150 мс, минимум107 мс. Baseline warm series отсутствует, поэтому сравнение cold→warm не выдаётся за улучшение приложения. Это также first display, без `fully drawn` и загрузки реальных пользовательских данных.

`baseline-startup-gfxinfo.txt` содержит только три кадра старта, все отмечены как janky; p50=450мс, p95=650мс. Это слишком короткий cold-start sample на SwiftShader, чтобы оценивать плавность списков, steady-state frame time или GPU реального телефона. GPU percentile из этого sparse histogram также не интерпретируется как аппаратная задержка. Perfetto/Android Studio CPU Profiler и длительная прокрутка на физическом устройстве не выполнялись.

## Память, потоки и дескрипторы

Исходные lifecycle-пробы запускали реальную `MainActivity`, переводили её в background/foreground, пересоздавали и закрывали. Проверялась собираемость Activity, decor и восьми ViewModel. Отдельная native-проба создавала и освобождала EGL, JavaAudioDeviceModule и PeerConnectionFactory; она не открывала PeerConnection, камеру, микрофон или сеть. После разогрева снимались значения после GC/finalization и дополнительного settling. Подробное владение ресурсами описано в [memory audit](ANDROID_MEMORY_LEAK_AUDIT.md).

| Workload / метрика | Baseline: warmup → settled | Промежуточный concurrent: warmup → settled | Финальный quiet: warmup → settled |
| --- | ---: | ---: | ---: |
| Activity: Java used, B | 5,490,128 → 5,506,416 | 5,494,224 → 5,506,480 | 5,481,936 → 5,494,128 |
| Activity: native allocated, B | 18,825,200 → 13,283,248 | 18,857,440 → 18,798,096 | 18,839,792 → 13,281,408 |
| Activity: total PSS, KiB | 225,401 → 222,553 | 226,161 → 226,760 | 225,482 → 222,637 |
| Activity: FD | 120 → 118 | 120 → 120 | 120 → 118 |
| Activity: threads | 31 → 33 | 33 → 34 | 32 → 33 |
| Activity: retained / watched после settling | 0 / 276 | 0 / 276 | 0 / 276 |
| Native factory: Java used, B | 5,531,264 → 5,547,648 | 5,527,184 → 5,543,568 | 5,514,896 → 5,535,376 |
| Native factory: native allocated, B | 18,096,256 → 18,096,192 | 18,885,552 → 18,101,472 | 18,093,552 → 18,093,104 |
| Native factory: total PSS, KiB | 231,401 → 231,381 | 231,306 → 231,094 | 231,141 → 231,101 |
| Native factory: FD | 122 → 122 | 123 → 122 | 122 → 122 |
| Native factory: threads | 33 → 33 | 34 → 34 | 33 → 33 |
| Native factory: retained / watched после settling | 0 / 330 | 0 / 330 | 0 / 330 |

Источники: `baseline-activity.json`, `after-activity.json`, `afterquiet-activity.json` (каждый: 3 разогревочных + 20 измеряемых циклов) и соответствующие `*-native-factory.json` (10 + 100 циклов). Все шесть проб завершены, collection assertions прошли; `final-quiet-lifecycle.json` отдельно фиксирует 2/2 PASS после APP006. В финальном quiet Activity Java растёт на 12,192 B, native уменьшается на 5,558,384 B, PSS уменьшается на 2845 KiB, FD120→118. Native factory Java растёт на 20,480 B, native уменьшается на 448 B, PSS уменьшается на40 KiB, FD122 стабилен. Небольшой рост Java включает сами списки WeakReference и измерительный код. Стабильного накопления native heap / FD за измеренные циклы и удержанных отслеживаемых объектов не выявлено. Это ограниченный workload, а не доказательство отсутствия всех утечек.

| Изменение счётчиков от warmup до settled | Baseline | Промежуточный concurrent | Финальный quiet |
| --- | ---: | ---: | ---: |
| Activity: cumulative process CPU | 5582 мс | 14,620 мс | 6359 мс |
| Activity: elapsed realtime | 20,307 мс | 42,886 мс | 22,002 мс |
| Native factory: cumulative process CPU | 1055 мс | 1106 мс | 1003 мс |
| Native factory: elapsed realtime | 12,908 мс | 13,072 мс | 12,799 мс |
| Оба workload: прирост UID RX / TX | 0 / 0 B | 0 / 0 B | 0 / 0 B |

Промежуточный after lifecycle выполнялся при параллельной работе второго AVD, backend fixtures и сборочных задач на хосте. После APP006 повтор quiet проведён без новых Gradle и других UI тестов. Его Activity CPU6359 мс / wall22,002 мс ближе к baseline5582 /20,307 мс, чем concurrent14,620 /42,886 мс; native CPU1003 мс против1055 мс baseline. Вариация показывает чувствительность эксперимента к окружению, но не доказывает конкретную причину различий. Это **не контролируемая серия benchmark до/после**: фоновая история, JIT/caches, GC и instrumentation не приведены к одному состоянию, повторных независимых серий недостаточно. Ни ускорение, ни причинная CPU-регрессия из этих значений не заявляются. Cumulative CPU не является latency одной операции или оценкой батареи. В after/afterquiet JSON абсолютные UID counters уже ненулевые после предшествующих live сценариев, но за офлайн-пробы не изменились; они не означают сетевую активность этих cycles.

Startup-снимок `baseline-startup-meminfo.txt` отдельно показывает PSS134,189KiB, RSS264,052KiB, Java allocated5790KiB и native allocated19,136KiB. Он снят в другой фазе и без того же post-GC протокола, поэтому его нельзя вычитать из lifecycle-таблицы. Значение Graphics0 в meminfo не доказывает отсутствие GPU allocations; gfxinfo отдельно показывает GPU cache8,970,724B и imported gralloc48,000.94KiB.

Дополнительный исправленный debug APK с опциональным LeakCanary проверен на 16KiB AVD: `final-leakcanary.json`, `leakcanary-activity.json`, `leakcanary-native-factory.json`. Две проверки прошли; Activity `assertNoLeaks` и проверка WeakReference успешны. После20 Activity cycles: 0/276 retained, Java5,938,064B, native18,789,264B, PSS241,723KiB, FD128, threads34. После100 native cycles: 0/330 retained, Java5,987,472B, native17,983,680B, PSS246,874KiB, FD130, threads34. Это отдельное окружение с дополнительным инструментированием, а не доказательство роста или снижения памяти относительно исходного 4KiB AVD; normal-build повтор на исходном AVD приведён отдельно выше.

## Действующее ограничение демонстрации экрана

Передача15 в `ScreenCapturerAndroid.startCapture/changeCaptureFormat` сама по себе не ограничивает FPS закреплённого SDK: аргумент называется `ignoredFramerate`. В клиенте применяется адаптация `VideoSource` до публикации, повторно при resize/new source, и дополнительный проверяемый `RtpParameters.Encoding.maxFramerate`. Ограничение камеры не менялось. Поток захвата не блокируется ожиданием; адаптация остаётся внутри WebRTC.

### Native тест с синтетической высокой частотой

Источник: `native-screen-fps.json`. Синтетический анимированный I420 input подаёт около144 кадров/с; native VP8 реально кодирует и декодирует. FPS рассчитан как прирост `outbound-rtp.framesEncoded`, делённый на разницу timestamps статистики. Наличие положительного контроля исключает маскировку ошибки слишком медленным producer.

| Фаза | Окно, с | Закодировано кадров | FPS | Декодированный размер |
| --- | ---: | ---: | ---: | --- |
| Без ограничения, positive control | 4.002 | 576 | 143.934 | 320×180 |
| Только адаптация source | 5.001 | 75 | 14.997 | 320×180 |
| Source + sender после resize | 5.002 | 75 | 14.995 | 180×320 |
| После stop | 1.005 | 0 | 0 | — |
| Новые source + track | 5.002 | 75 | 14.993 | 320×180 |
| Новый PeerConnection | 5.001 | 75 | 14.996 | 320×180 |

Этот тест подтверждает действие адаптации native SDK и сохранение ограничения при пересоздании объектов. Это синтетические144fps, а не MediaProjection с физического экрана144Гц и не reconnect через реальный сервер.

### Реальный MediaProjection на эмуляторе

Источники: `final-screen-projection.json` и `final-screen-projection-fps.json`. Пройдены системное согласие, production `ScreenShareService`, capture production `CallEngine`, два реальных поворота, остановка и перезапуск с новым согласием. Setup PeerConnection/SDP подменён тестовым локальным loopback; production reconnect и серверная запись не входят в сценарий. Экран AVD сообщает60.000004Гц; первая сессия активна31.151с.

| Фаза | Окно, с | Прирост framesEncoded | FPS | Capture / decode |
| --- | ---: | ---: | ---: | --- |
| Portrait | 5.004 | 75 | 14.989 | 450×720 / 450×720 |
| Landscape после поворота | 5.008 | 77 | 15.375 | 1152×720 / 1152×720 |
| Portrait после второго поворота | 5.019 | 73 | 14.544 | 450×720 / 450×720 |
| Продолжение первой сессии | 9.527 | 131 | 13.751 | 450×720 / 450×720 |
| После stop | 1.004 | 0 | 0 | — |
| Перезапуск с новым согласием | 5.006 | 77 | 15.383 | 450×720 / 450×720 |
| После повторного stop | 1.001 | 0 | 0 | — |

Во всех рабочих окнах sender сообщает maxFramerate15. Короткие окна после перехода дают до15.38FPS, поэтому результат означает устойчивую адаптацию примерно до15FPS, а не математический предел15 в каждом произвольном окне. Capture приходит с частотой примерно21.7–35.4FPS и снижается до кодирования; растягивания/нежелательной обрезки в измеренных decode dimensions не выявлено. Счётчики и владельцы screen resources после остановки очищены. Ранняя попытка этого теста завершилась ошибкой fixture «Test activity did not change orientation»; успешный финальный прогон не скрывает эту первоначальную проблему harness.

## Отзывчивость lifecycle cleanup

На frozen baseline APK два gated-теста не получили освобождение камеры/микрофона за3с, пока actor ждал lookup/presence; отдельный screen-stop тест также не прошёл. После исправления приоритетные pause/leave/stop отменяют текущую операцию, получают сериализованное владение cleanup и защищены от поздних результатов и старых call/screen intents. Если отменена незаконченная SDP-транзакция, соединение безопасно закрывается с явным повторным подключением вместо продолжения частичной транзакции.

`final-media-priority.json`: 13/13 PASS, включая восемь actor/intent регрессий, две screen lifecycle проверки и три actual ActivityResult/lifecycle проверки permission. Это контролируемые gates и тестовые capturers/owners; результат не является измерением задержки любой сети. Удалённый leave после локального освобождения может ждать сетевой timeout; ресурсы capture к этому моменту уже освобождены. Уже работающая idle foreground screen share сохраняется при device-only pause.

## Размеры артефактов и 16KiB

| Артефакт | Baseline, B | Итоговая сборка после APP006, B |
| --- | ---: | ---: |
| Debug APK | 78,669,423 | 84,263,200 |
| Minified release APK | 52,892,512 | 52,908,936 |
| Release AAB | 32,519,956 | 32,549,194 |

Источники: `baseline-artifact-verification.json`, `final2-artifact-verification.json` после APP006. Debug hash совпадает с итоговым startup sample; hashes сохранены в test report и evidence/build-artifacts.json. Release APK/AAB не подписаны постоянным release key. ZIP alignment и64-bit PT_LOAD16KiB проходят статический verifier; замечания о GNU_RELRO сохранены в JSON.32-bit WebRTC libraries имеют4KiB PT_LOAD; из этого не выводится несовместимость64-bit Android.

`device-16k.json` и `after-native-16k.json` подтверждают загрузку `androidx.graphics.path`, `datastore_shared_counter`, `jingle_peerconnection_so` и создание/освобождение WebRTC factory на API36 ARM64 с page size16384 и **отключённым compatibility mode**. `final2-release16k-smoke.json` дополнительно подтверждает cold startup и видимость first-run UI минифицированного release, временно подписанного тестовым ключом. Это loader/startup smoke одного ARM64 AVD; x86_64, остальные Android/OEM и полный звонок в release не сертифицированы.

## Память установщика

Подтверждена пропорциональная размеру dump аллокация в Android-bundled Python installer: три чтения полного backup заменены потоковым hash и файловым stdin `pg_restore --list`. На синтетическом32MiB dump исходное выражение hash дало peak traced Python heap33,559,058B; полный исправленный phase с mocked Docker dump/restore — 2,136,534B. Объём работы в двух замерах различается: это доказательство bounded buffer use, а не сопоставимая оценка VPS RSS или реального восстановления PostgreSQL. Восемь focused регрессий включают8/64MiB dumps и peak<4MiB. Источники и условия приведены в [data findings](baseline-data-findings.md).

Потоковый backup — целевое изменение расхода памяти в рамках этого аудита. Остальные исправления относятся к корректности, безопасности и своевременному освобождению ресурсов; ускорение startup, снижение UI CPU или общего PSS ими не заявляется. Native screen FPS проверяет уже имеющуюся политику ограничения и её сохранение при lifecycle transitions.

## Неподтверждённые области

- Cold и warm startup, concurrent и quiet lifecycle числовые прогоны завершены. Для причинного performance вывода нужны повторные контролируемые серии; baseline warm series отсутствует.
- Нет physical144Гц MediaProjection, аппаратного encoder, продолжительного звонка/записи через SFU с несколькими границами сегментов, измерения батареи/нагрева и длительного network handoff.
- Нет репрезентативных UI scroll frame timings, CPU/Perfetto trace, p95/p99 из большой выборки, Android Studio GUI Memory Profiler и сопоставимого GPU memory workload.
- Не сделан вывод об отсутствии всех утечек: измерены конкретные офлайн-владельцы и отдельные native/MediaProjection сценарии. Живые messaging/recordings UI E2E и SAF прошли, их область и первоначальные ошибки перечислены в [test report](ANDROID_TEST_REPORT.md).
