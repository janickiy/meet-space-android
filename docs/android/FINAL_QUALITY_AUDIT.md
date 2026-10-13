# Финальный аудит качества Android — этап 09

Дата: 2026-10-11. Проверены исходная **0.8.0 (7)** и исправленная **0.9.0 (8)**. Подтверждены и исправлены четыре runtime-дефекта: незакрытый signaling при отказе ICE, отложенное удержание released Media3 player, неатомарное начало remote installer и фоновый installer polling. Для них выполнены отдельные воспроизведения и regression/heap проверки. Общего ускорения приложения либо готовности к публичному выпуску эти результаты не доказывают.

Завершены доступные локальные baseline/after измерения, функциональные и package/runtime проверки. В проверенной области не осталось подтверждённых критических утечек, гонок или security blockers; непроверенные устройства, VPS rehearsal и внешние условия выпуска перечислены отдельно. Основание — запрос пользователя и `CODEX_ANDROID_09_09_FINAL_OPTIMIZATION_QA.md`.

## План до исправлений

1. Сохранить исходники, SHA APK/AAB, состав зависимостей и параметры среды. Измерить исходное поведение неизменённой production-реализации с test-only harness: heap после GC/прогрева, retained Activity/ViewModel, native heap, потоки, FD, CPU, startup, frames и трафик где доступны.
2. Независимо проверить lifecycle/media, TLS/auth/данные/зависимости и SSH/installer. Подтверждать конкретные дефекты воспроизведением. До baseline production-код не менять; исправления выполнять небольшими партиями.
3. Повторить одинаковые измерения после исправлений. 100+ join/leave циклов выполнять на изолированном Pion-стенде, если его фактическая конфигурация позволяет. Анализировать heap dumps и GC roots; RSS не считать доказательством утечки.
4. Запустить Gradle unit/lint/UI, доступные функциональные E2E со стендами, Android matrix и проверки installer. Backend production не менять. Если backend-исходники потребуют исправлений, отдельно выполнить Go test/race/vet и серверный regression.
5. Подготовить воспроизводимый CI, debug-only инструменты утечек при совместимости, APK/AAB и artifact/security verification. Зафиксировать ограничения и инструкцию подписания. Очистить только собственные стенды и тестовые данные.

## Среда и границы измерения

| Параметр | Фактические условия |
| --- | --- |
| Host/toolchain | Mac17,3,10 CPU,24GiB RAM, macOS 27.0.1 ARM64; JDK17; Gradle8.13; compile SDK36 / Build Tools35.0.0 |
| Измерительный Android | Отдельный AVD `emulator-5556`, Android16/API36, ARM64, page size4096; свежий private userdata, без пользовательских credentials старого AVD |
| Fingerprint | `Android/sdk_phone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:userdebug/test-keys` |
| RAM/heap | Запрошенные1536MiB эмулятор повысил до4096MiB; `/proc/MemTotal=4014920kB`; heap growth limit192MiB, heap size576MiB; low-RAM устройство не подтверждено |
| Graphics/UI | AVD настроен на2560×1600, density320, SwiftShader; font scale1.0 для измерительной конфигурации. Phone/font/API matrix учитывается отдельно |
| Вариант | Debug, одинаковый test harness; LeakCanary отключён в обоих основных измерениях. Отдельный opt-in не смешивается с baseline/after |
| Heap/CPU | Explicit GC/finalization, warmup/end HPROF, Shark2.14 CLI/JDK17; 45s Perfetto58.2 для Activity/recording. Android Studio отсутствует, GUI Memory Profiler не использовался |
| Backend | Disposable локальные HTTPS/WSS/Pion/TURN и recordings fixtures. Production `meeting.janickiy.com` не использовался и не менялся |
| Provenance | Android workspace не Git checkout; сохранён source SHA inventory. Чистый backend checkout закреплён на `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69` |

[Исходные условия](evidence/09/environment-baseline.json), [фактическая память измерительного AVD](evidence/09/measurement-device.json). Параметры RAM из запроса не выдаются за фактическое ограничение устройства. HPROF, полные raw logs и fixture inputs остаются приватными: heap может содержать тестовые credentials. Опубликованы только числовые/обезличенные aggregates.

Baseline debug APK SHA256: `16807136a0152e08005d5ba2261593b18f378dec9934b4f102264eefed9f1802`. After performance APK SHA256: `b793443b035f9346b403fc650d37bc4a13448bac183226bc143ed9b15727ef2e`. Это идентичность измеренных бинарников, не финальная подпись для распространения. Последующие opt-in/test builds не подменяют сохранённые результаты.

## Подтверждённые дефекты и исправления

| Дефект до исправления | Минимальное изменение | Проверенное подтверждение |
| --- | --- | --- |
| После `openCall` ошибка ICE возникала до передачи signaling владельцу; cleanup не закрывал новый WebSocket | Присваивание `signaling=session` сразу после получения, до валидации ICE | Baseline regression: создан1, закрыт0 вместо1. After `CallSignalingLifecycleTest.rejectedIceClosesAlreadyOpenedSignaling`: `OK (1 test)` |
| Released ExoPlayer удерживал duration timer Media3 1.11.1; после5s оставался1 player, Java retained29031B/772objects | Сохранить position/playWhenReady, снять listener/observer, `stop()` перед `release()` в finally | After все46 player weak refs собраны; warmup/end/idle HPROF: player/internal/StuckPlayerDetector/messages0. Реальное pause/seek/rotation воспроизведение прошло |
| Первый installer apply мог оставить plan/bundle без marker/state; reconnect/resume не восстанавливал эту новую установку | Полный mode0700 layout готовится и fsync выполняется в соседнем staging; same-filesystem atomic rename публикует его перед запуском worker | Две исходные fault точки воспроизведены; after23 прежних+8 новых Python tests, включая ошибки8 writes и abrupt exits, PASS. Signature Verified OK, trust root прежний |
| Installer status polling продолжался каждые3s при Activity в фоне | STARTED gate, отмена status loop на STOPPED, observation epoch для stale callbacks; remote job и явный apply не отменяются | Baseline40 вызовов за120s виртуального времени. After13/13 VM tests, включая5 foreground/stale/cancel regressions |

Подробные причины, правила владения и ограничения: [lifecycle/heap](09_LIFECYCLE_AUDIT.md), [SSH/installer](09_INSTALLER_AUDIT.md). Исправление remote recipe не ремонтирует произвольный чужой или старый неполный `/opt/meetspace`; старые tickets не мигрируют автоматически. Подписанный runner/release manifest изменены с сохранением signer identity и backend commit.

Дополнительно исправлено включение точного BSD notice JSch в assets, подготовлены независимые artifact checks, CI и opt-in LeakCanary. Это исправления комплектации/проверяемости, не дополнительные runtime-уязвимости. Для JVM SSH task добавлена зависимость от пяти файлов signed installer bundle, чтобы Gradle не считал старые тесты актуальными после замены recipe. Свежий SSH run прошёл57/57; итоговая проверка пакетов подтвердила наличие точного license asset.

Backend production-исходники не изменены. В `testing/stage06/main.go` добавлены только метрики тестовой сборки (`runtime.GC`, goroutines/heap snapshot). Это не runtime-патч развёрнутого backend.

## Сопоставимые измерения памяти и ресурсов

[`measurement-summary.json`](evidence/09/measurement-summary.json) с SHA исходных JSON получен командой `python3 testing/stage09/summarize_measurements.py`. Проверены статусы всех8 workloads, суммы циклов, монотонность времени/CPU, границы heap/weak refs и пересчёт статистики7 startup samples. Таблица показывает **warmup → settled после5s**, MB десятичные. `native` — выделенные bytes (`nativeHeapAllocatedBytes`), а не ёмкость allocator (`nativeHeapBytes`).

| Сценарий | Вариант | Warmup + measured | Java used MB | Native allocated MB | Threads | FD |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Factory/ADM/EGL | baseline | 10 + 100 | 2.904 → 2.961 | 11.318 → 11.327 | 18 → 18 | 103 → 103 |
| Factory/ADM/EGL | after | 10 + 100 | 2.912 → 2.969 | 11.318 → 11.327 | 18 → 18 | 103 → 103 |
| Реальный call join/leave | baseline | 10 + 100 | 5.782 → 5.794 | 19.513 → 19.716 | 40 → 50 | 139 → 139 |
| Реальный call join/leave | after | 10 + 100 | 5.708 → 5.786 | 19.522 → 19.720 | 41 → 51 | 139 → 139 |
| Activity + запись | baseline | 3 + 20 | 6.965 → 6.947 | 19.868 → 20.064 | 52 → 57 | 167 → 160 |
| Activity + запись | after | 3 + 20 | 6.910 → 6.955 | 19.873 → 20.072 | 53 → 59 | 155 → 155 |
| Login/logout | baseline | 3 + 20 | 6.060 → 6.081 | 19.653 → 19.658 | 40 → 39 | 135 → 135 |
| Login/logout | after | 3 + 20 | 6.126 → 6.150 | 19.671 → 19.684 | 41 → 40 | 144 → 153 |

Factory workload создаёт и освобождает native factory/ADM/EGL без захвата; все110 weak refs каждой категории собраны. Call workload использует один application engine, реальные WSS/Pion/ICE/TURN, camera/mic/screen выключены. Он проверяет CONNECTED, периодический relay diagnostic и корректный IDLE/leave, но не число участников или100 разговоров с encode/decode. Activity workload проходит реальную запись/native frame, recreation, закрытие player/route и Activity; auth — реальный login/logout одного fixture-профиля, без фиктивного server-switch.

После Activity workload в обоих вариантах собраны46 Activity/decor/PlayerView и23 каждого из четырёх VM. Отличие player подтверждено не общим heap размером, а исчезновением конкретного GC-root path: baseline1 released player после5s, after0. В after warmup/end/idle отсутствуют также StuckPlayerDetector и его сообщения; стандартные Android Shark filters дают0 application/library groups. Это не универсальная гарантия отсутствия любых утечек. [Обезличенный анализ](evidence/09/09heap-analysis.json).

Для звонка дополнительный65s idle выполнен в обоих вариантах. Baseline threads50→45, after51→46; FD139→136 в обоих. HPROF показывает завершение свободных OkHttp/Okio workers; PC/factory/ADM0, один engine и foreground Activity намеренно остаются живыми. Binder roots baseline4→15→15, after5→16→16; это наблюдаемые прогретые process pools, не универсальный системный лимит. [Baseline thread analysis](evidence/09/baseline-call-thread-hprof.json), [after](evidence/09/after-call-thread-hprof.json).

Для Activity after дополнительный idle дал FD155→147 и native allocated20.072→14.497MB; соответствующего baseline idle нет, поэтому это не парное сравнение. Для auth выполнена дополнительная3+20 серия с65s idle: FD144→153 на13-м цикле,153 на23-м и150 после idle; threads38→37→32, native allocated19.645→19.658→19.575MB; все23 session/detail weak refs собраны. Рост по каждому циклу не наблюдался, но до исходных144 FD число не вернулось; destinations не анализировались. Поэтому не заявляется полное освобождение каждого descriptor либо идеальная стабильность всех FD. [Дополнительная серия](evidence/09/after-idle-auth.json). Первый дополнительный запуск сохранился отдельно как `after-auth-repeat-no-idle.json`: старый harness игнорировал idle argument для auth; после расширения test-only параметра повторён именно65s idle.

Ёмкость native heap увеличивается примерно на41–45MB в обоих вариантах при почти неизменных allocated bytes. RSS/PSS также меняются после dump/allocator работы; они сохранены в summary, но не трактуются как доказательство leak или memory optimization. Например factory RSS324888→332448KiB baseline и324864→333112KiB after. По этим данным не обнаружено after-only роста native allocated memory; универсальный вывод об отсутствии native leaks не делается.

## CPU, трафик, startup и кадры

Ниже **baseline / after**, один прогон каждого варианта. Время включает instrumentation, explicit GC, warmup HPROF overhead и5s settling; это не latency отдельных пользовательских операций. UID traffic включает работу процесса и control transport, не доказывает качество media throughput.

| Сценарий | Wall seconds | Process CPU seconds | UID RX MB | UID TX MB |
| --- | ---: | ---: | ---: | ---: |
| Factory/ADM/EGL | 13.323 / 13.269 | 1.563 / 1.521 | 0.000 / 0.000 | 0.000 / 0.000 |
| Реальный call join/leave | 149.170 / 148.949 | 9.736 / 9.623 | 2.578 / 2.580 | 2.844 / 2.843 |
| Activity + запись | 84.267 / 87.353 | 39.551 / 42.263 | 13.177 / 12.890 | 0.200 / 0.198 |
| Login/logout | 16.401 / 17.380 | 1.855 / 2.245 | 0.324 / 0.324 | 0.067 / 0.067 |

CPU/wall Activity и auth после изменения в этом запуске выше, другие различия малы. Единственного такого прогона недостаточно для вывода о причинной регрессии либо ускорении. Allocation/GC deltas опубликованы; физическая battery, thermal throttling и энергопотребление не измерены.

Startup:2 warmup исключены,7 force-stop launches без очистки данных. `am start -W TotalTime` median **942ms baseline /870ms after**; min/max840–1768 /773–2147ms. Это first display, не fully drawn. Разброс и малая выборка не позволяют объявить доказанное ускорение на фиксированный процент. [Baseline](evidence/09/baseline-startup.json), [after](evidence/09/after-startup.json). Первый after запуск пересекался с тяжёлой artifact scan и дал median1425ms; он [сохранён отдельно](evidence/09/after-startup-concurrent-artifact-scan.json) и исключён из сопоставления по заранее выявленной разнице нагрузки, а не скрыт.

Perfetto охватывает первые45s debug Activity/recording с SwiftShader, GC и heap dump: baseline406 surface frames,210 App Deadline Missed (**51.72%**); after391/202 (**51.66%**). Scheduled CPU26.143/28.325s; trace error counters пусты. Это тяжёлый тестовый сценарий, не scrolling benchmark или частота пропусков кадров на физическом телефоне. Улучшение плавности не подтверждено. Полные категории: [baseline](evidence/09/baseline-perfetto.json), [after](evidence/09/after-perfetto.json).

## SFU и достоверность harness

В финальных before/after snapshots fixture PC counter вырос ровно на110 в каждой серии; число failures не увеличилось. Baseline goroutines17→16 и Go heap3.672→3.643MB; after16→16 и3.841→3.686MB. Rooms/media peers/tracks/subscriptions на финальных снимках0; forwarded packets0. Это подтверждает освобождение контрольного подключения в данном стенде, не media throughput и не нагрузку production. Host open-file records из `lsof -F` включают mappings, поэтому не называются точным числом Go FD.

В историческом baseline SFU observer есть недостоверный ранний `settled`: всего2s после before, completed110 из старого cache JSON при неизменном PC counter. Он исключён из текущей серии, baseline batch curve не заявляется; годными остаются финальные counters и Android JSON. Raw history не переписана; assessment входит в [summary](evidence/09/measurement-summary.json). На будущие запуски observer перед instrumentation удаляет только точный cache result данного label; condition больше не утверждает наличие постоянного echo peer. [Baseline SFU](evidence/09/baseline-sfu-lifecycle.json), [after SFU](evidence/09/after-sfu-lifecycle.json).

Первый Activity baseline завершился до первого цикла: polling через Thread.sleep не продвигал Compose clock, native AndroidView не появился вовремя. Это [сохранённый сбой harness](evidence/09/harness-initial-activity-failure.json), а не измеренная product regression. Test-only ожидание заменено на Compose waitUntil, после чего baseline/after используют одинаковый исправленный harness. Первый call short-run без65s idle сохранён отдельно и не смешивается с повторным парным измерением.

## Безопасность, лицензии и installer

[Ограниченный security audit](09_SECURITY_AUDIT.md) проверил HTTPS/origin/auth boundaries, scoped recording access, logout, storage redirects/credentials, SAF, notification intent и фактический baseline release manifest. В просмотренных границах не подтверждены новый auth bypass, IDOR или отправка bearer в object storage. Это не penetration test и не утверждение об отсутствии всех уязвимостей.

OSV для178 точных Maven coordinates дал178 ответов и0 совпавших advisories на дату проверки; embedded native WebRTC/кодеки и неизвестные дефекты этим не покрыты. Для178 POM собраны license declarations. Отсутствовавший основной JSch BSD notice добавлен точными исходными байтами; final APK/AAB verifier подтвердил SHA обязательного notice во всех трёх пакетах. Firebase/gateway отсутствуют: фоновые push, token rotation и gateway delivery не считаются проверенными.

Installer:23 прежних+8 новых Python tests и5 локальных protocol checks PASS, подпись нового recipe Verified OK. Использованы реальные local file writes/fsync/flock/child termination с изолированными host facts. Полный VPS/DNS/ACME/systemd/Docker install/upgrade/backup/restore/rollback и power-loss rehearsal **не измерены**. Runtime SSH credentials пользовательских серверов не использовались. Детали и строгие пределы выводов — [09_INSTALLER_AUDIT.md](09_INSTALLER_AUDIT.md).

## Итоговые проверки

Gradle unit/lint/debug/test/release/AAB: **BUILD SUCCESSFUL**. JVM **271/271 PASS**, все suite свежие, включая отдельный core/ssh57/57 после объявления signed bundle task inputs;9 live backend tests без skip. Python **39/39 PASS** (23 installer +8 initialization +8 artifact verifier). App lint debug/release:0 errors/46 warnings на вариант; в core/media2 warnings на вариант, в остальных проверенных core модулях0. Подробности и сохранённые XML: [09_TESTING.md](09_TESTING.md).

| Android проверка | Результат и границы |
| --- | --- |
| API36 ARM64/4KiB, tablet2560×1600,density320,font1.0 | 77 offline Compose/native PASS;4 real recordings PASS;5 chats/folders/files/WSS/SAF PASS |
| Camera/mic/TURN/rotation/reconnect |4/4 LiveCall PASS, включая screen projection, остановку из уведомления, radio loss, недоступный TURN и восстановление |
| Denied permissions | Camera denial/listening1/1 PASS; новый notification teardown regression с начальным POST_NOTIFICATIONS denied1/1 PASS |
| Phone1080×1920,density480,font1.5 |25 UI PASS;1 tablet-only test skipped по условию ширины, этот же тест PASS в tablet suite;4 recordings PASS |
| Strict16KiB API36 ARM64 | На final debug APK5/5 native+recordings PASS; linker compatibility=false, pm compatibility disabled=true. Дополнительно actual camera/mic/relay/reconnect1/1 PASS на обычном APK с идентичными290 unpacked entries |
| Minified release из finalAAB | Временно подписанный smoke APK установлен после удаления debug/test APK; старт без instrumentation/private CA PASS, обе onboarding actions видны; auth/playback release не проверялись |
| Настоящий split-screen release | Через launcher Split + Settings, resize окна до432dp: multi-window=true, обе actions видны, форма подключения открывается; authenticated call в split-screen не проверен |
| LeakCanary2.14 |3 warmup+3 Activity/player cycles, `LeakAssertions.assertNoLeaks` вернулся без application leak assertion; отдельный диагностический вариант |

[Android результаты](evidence/09/android-runs/), [LeakCanary](evidence/09/leakcanary-activity-recording-leakcanary.json), [16KiB runtime](evidence/09/runtime-16k.json), [фактический installed APK hash/настройки](evidence/09/strict-16k-device.json), [split-screen](evidence/09/release-multiwindow.json). Скриншоты release просмотрены: [tablet](evidence/09/release-smoke-16k.png), [половина экрана](evidence/09/release-split-screen-16k.png), [432dp](evidence/09/release-split-narrow-16k.png).

Исправлены и отдельно сохранены сбои проверок: Compose polling clock в первом baseline; self-revoke notification permission, останавливавший собственный runner; fail-fast retry, читавший прежний FAILED до обработки actor; XML parser, склеивавший JVM stderr с manifest stdout. После узких test/tool fixes соответствующие проверки повторены и прошли. Эти сбои не скрыты и не выдаются за дополнительные production-дефекты. Счётчики контрактов backend: meetings60, messaging460, recordings758 проверенных assertions — это не число пользовательских сценариев. Go test/race/vet в этом этапе отдельно не запускались: production backend не менялся, реальные test assemblies были собраны и использовались в интеграционных сценариях.

## Артефакты и воспроизводимость

Все три пакета0.9.0(8) прошли static packaging/manifest/auth-marker/private-material/installer-signature/license checks. Просканированы588 известных byte patterns из31 доступного private input файла; совпадений секретов/fixture markers в поставке нет. Это ограниченный корпус, не универсальный secret scan. LeakCanary и test harness не входят в обычную поставку. Итоговый verifier дополнительно связал runtime report с SHA пакетов и DEX/native/assets smoke APK из текущего AAB.

| Артефакт | Bytes | SHA256 |
| --- | ---: | --- |
| debug | 82,468,478 | `721cd11273e9d60984a195922d34a77196c86c20e6774d88c56dead48b288864` |
| release | 52,002,471 | `aec4056dd7f4055299048068d52351116c8f81e8f87fc231b8ca01e183ab2c59` |
| releaseBundle | 30,742,731 | `ddc48905ab5054bfdaaf6ecc7f58a21955d3a8483fc94fafa079ad75704ea21f` |

Файлы: [debug APK](../../app/build/outputs/apk/debug/app-debug.apk), [unsigned release APK](../../app/build/outputs/apk/release/app-release-unsigned.apk), [unsigned AAB](../../app/build/outputs/bundle/release/app-release.aab). [Полный verifier report](evidence/09/artifact-verification.json). Обычный debug после LeakCanary пересобран без opt-in; все290 unpacked entries совпадают с измеренным after APK, отличие полного ZIP SHA явно сохранено в [equivalence evidence](evidence/09/normal-rebuild-equivalence.json). 16KiB runtime повторён именно на новом final APK SHA.

Статус verifier — `STATIC_PACKAGING_CHECKS_PASSED_WITH_RELRO_FINDINGS`: PT_LOAD/ZIP16KiB checks PASS, но формальные GNU_RELRO end alignment findings остаются у graphics-path ARM64/x86_64 и WebRTC x86_64. Ограниченный strict ARM64 runtime PASS не подтверждает x86_64, все native операции или физические устройства; finding не подавлен и требуется оценка перед распространением.

Release keystore не предоставлен: постоянная подпись, проверка обновления с ключом владельца и публикация не выполнены. Temporary smoke identity не является release key. [Инструкция выпуска](09_RELEASE.md), [portable QA/CI](../../testing/stage09/README.md), [workflow](../../.github/workflows/android-quality.yml). Workflow и shell snippets проверены локально, GitHub Actions run не выполнялся — Android workspace не Git checkout.

Основные изменённые production файлы: CallEngine, RecordingPlayer, InstallerViewModel/MainActivity lifecycle, installer runner и signed release recipe, JSch license asset, app Gradle version/optional diagnostics и SSH test task inputs. Test harness, инструментальные helpers и CI перечислены в [source-changes](evidence/09/source-changes.json);12 baseline-covered files modified,126 unchanged, удалений0. [Независимый review](09_FINAL_SOURCE_REVIEW.md) не нашёл новых actionable замечаний в исправлениях. Source SHA inventory не является полным textual Git diff; первоначальный список не покрывал часть test/doc деревьев.

## Очистка стендов

Остановлены и удалены4 собственных fixture homes (процессы, PostgreSQL, временные Redis/MinIO containers и тестовые CA/данные), оба созданных AVD5556/5558 и их userdata. Fixture containers осталось0. Сохранены те же11 ранее работавших пользовательских Docker containers: ID/image/name/state совпадают;54 ранее остановленных контейнера не затронуты. Исходный emulator5554 доступен, размер/density/font/network settings совпадают с baseline; backend checkout чистый. [Проверка cleanup](evidence/09/cleanup.json). Production не менялся.

Raw heap/trace/log файлы сохранены в игнорируемом `.audit/stage09` с закрытым каталогом0700; они могут содержать тестовые credentials и не входят в публичные evidence или APK/AAB. Публичные114 файлов перед удалением fixtures дополнительно проверены по572 известным private patterns: совпадений0 ([scan](evidence/09/public-evidence-secret-scan.json)). Существующий ключ подписи installer сохранён; release app key не создавался.

## Ограничения и внешние условия выпуска

- Физические телефоны/планшеты, low-RAM/LMK, Bluetooth/headsets, OEM background restrictions, real camera/mic quality, battery/thermal и cellular handover не подтверждены этими эмуляторными измерениями.
- Auth stress покрывает один origin/account login/logout; реальный switch между двумя серверами не измерен. Дополнительная серия FD/idle учитывается отдельно от парного baseline.
- Стресс100 join/leave выключает media capture. Короткие реальные camera/mic/screen-share tests прошли отдельно; packet loss/bandwidth/jitter,100 media conversations и многочасовой звонок с участниками не измерены.
- Матрица выполнена на API36 ARM64: API26–35, x86_64, физические7–13-дюймовые планшеты и TalkBack не проверены. GNU_RELRO замечания перепроверены, сохранены в final report и остаются условием внешнего выпуска.
- Полный disposable VPS rehearsal, постоянный release key, Firebase/gateway и публичная privacy policy остаются внешними условиями выпуска.
- HPROF/fixture secrets не публикуются. Source inventory при отсутствии Git позволяет сравнить hashes, но не заменяет commit/PR history или независимую проверку всей поставки.

