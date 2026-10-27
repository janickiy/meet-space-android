# MeetSpace Android — исходное состояние аудита, 27 октября 2026

Этот отчёт создан до правок рабочего кода в рамках текущего аудита. Исходное состояние — HEAD `bb806e0b75ec720a695347b27d5d22d98a15b9a8` плюс 37 изменённых/новых файлов предыдущих задач уведомлений и записей. Эти изменения сохраняются. Их хэши и patch сохранены локально в `.audit/android-audit-20261027/pre-audit-files.json` и `pre-audit.patch`; это приватные рабочие материалы, не публикация. Аудит проводится по приложенному CODEX_PROMPT_ANDROID_AUDIT.md. Реальные серверы и VPN не изменяются.

## Инвентаризация

| Область | Реальное состояние |
| --- | --- |
| Модули | `:app`, `:core:model`, `:core:data`, `:core:designsystem`, `:core:media`, `:core:ssh` |
| Сборка | Gradle wrapper 8.13 с SHA-256; AGP 8.13.2; Kotlin/Compose compiler 2.2.21; JDK 17.0.20.1; Java target17 |
| Android | min26, target/compile36; debug и minified release; 0.3.11-beta, versionCode29 |
| UI | Compose BOM2025.10.01, Material3, adaptive1.2.0, Window1.4.0, Activity1.11.0, Lifecycle2.9.4 |
| Сеть | OkHttp4.12.0, HTTPS REST, авторизованные WebSocket; kotlinx.serialization1.9.0, coroutines1.10.2 |
| Данные | DataStore1.2.1, Android Keystore-backed token storage; отдельные server/account scopes; приватные временные файлы/SAF; Room отсутствует |
| Медиа | WebRTC144.7559.15, Pion/SFU signaling; MediaProjection + неэкспортируемый foreground service; Media3 ExoPlayer/UI1.11.1 |
| SSH | mwiede JSch2.28.8, BouncyCastle1.86; TOFU/host pin store; preflight; подписанный Python runner и release manifest, Docker install/prebuilt/build modes |
| Уведомления | локальные Android channels + foreground realtime; Firebase Messaging25.0.1 и WorkManager2.10.5 присутствуют; google-services.json отсутствует, FCM отключён |
| Реализованный продукт | серверы/аккаунты, регистрация/вход/гость, встречи/календарь, групповые/личные звонки, чаты/папки/файлы, записи/обложки, настройки, SSH-установка |
| Manifest | INTERNET, NETWORK_STATE, CAMERA, RECORD_AUDIO, audio settings, Bluetooth, media-projection FGS, notifications; hardware camera/microphone optional; backup запрещён; cleartext запрещён; MainActivity launcher экспортирована; пользовательские services не экспортированы |
| Выпуск | env-only release signing; четыре переменные сейчас не заданы; релиз будет unsigned. CI Android quality: unit/lint/APK/AAB, Python installer tests, выбранные offline instrumented tests, artifact verification; публикации нет |
| Хост | macOS Darwin27 arm64, 24GiB RAM; Android SDK локально, доступен API36 arm64 emulator; физического устройства нет |
| Средства | Gradle/JUnit/Android lint, Python unittest, adb/dumpsys, native WebRTC smoke/stress tests; optional LeakCanary2.14; локальный bundletool1.18.3 и Perfetto tool доступны, использование отдельно документируется |

## Первичные приоритеты и ограничения (до исправлений)

- **P1 / выпуск:** постоянная release-подпись не настроена в окружении. Текущий debug APK нельзя считать совместимым обновлением ранее опубликованного APK без проверки сертификата. Не заменять ключи и не публиковать сборку в ходе аудита.
- **P2 / supply chain:** отсутствует Gradle `verification-metadata.xml` и отдельный dependency-vulnerability task. SHA wrapper и SHA CI actions есть, но это не проверка всех артефактов Maven. Выполнить свежие exact-version OSV-запросы и описать границы проверки.
- **P2 / доставка уведомлений:** FCM не настроен; работоспособность background push не доказана и не должна выводиться из foreground тестов.
- **Открыто:** lifecycle/memory/auth/file/installer/media аудит выполняется параллельно; потенциальные проблемы не считаются подтверждёнными до проверки кода/тестов. Подтверждённые находки будут добавлены ниже до их исправления.

## Воспроизводимая исходная проверка

Задачи сначала обнаружены через `./gradlew tasks --all --console=plain`, вывод: `.audit/android-audit-20261027/gradle-tasks.log`.
Запущена до правок: `./gradlew testDebugUnitTest :core:model:test lintDebug lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease :app:dependencies --configuration releaseRuntimeClasspath --console=plain`. Лог: `baseline-gradle.log` в той же папке. До окончания не считается успешной.
Python: `python3 -m unittest discover -s installer/tests -p 'test_*.py' -v` — 126 passed; проверка `testing/stage09/test_*.py` запущена отдельно.

Создан отдельный одноразовый AVD `MeetSpace_Audit_20261027` (API36 arm64, RAM4GiB, 2560×1600/density320, SwiftShader, emulator-5556); существующий пользовательский AVD не изменён. План измерений: холодный/тёплый запуск, offline UI и native ресурсные циклы; реальные 2–5-сторонние звонки, оборудование/Bluetooth, батарея и полноценная установка на disposable VPS пока не проверены. Прежние отчёты и результаты предыдущей задачи записей не заменяют свежие проверки этого аудита.

Начальные p50/p95, heap/native/graphics, CPU/jank/network/FD/thread метрики ещё не измерены. В отчётах будут только реально полученные значения с условиями и размером выборки; успешная сборка не является доказательством безопасности/отсутствия утечек.


## Подтверждённые находки app (до исправлений)

- **APP-001 / P2 / очистка экспорта:** `MessagingViewModel.kt:297–307` и `CallDiscussionViewModel.kt:375–387` принимают экспорт SAF, но вызывают очистку только из тела coroutine. Общий `request()` создаёт `LAZY` job; `back()`/`clear()`/logout до первого исполнения отменяет её без входа в тело и без `onFailure`. Новый пустой SAF-документ остаётся. Воспроизведение регрессионным тестом: подготовить вложение, вызвать `export(onFailure = counter++)`, затем `back()` или `clear()` до `runCurrent()`; ожидается одна очистка, текущий код её не вызывает. Это подтверждение анализом управления; новый тест до исправления отдельно не запускался. Исправление должно запускать очистку по завершению принятого job, включая отмену до старта, и не удалять успешный экспорт.
- **APP-002 / P2 / отзыв доступа:** `MeetingsViewModel.kt:239`/`:254`/`:264` сохраняют прежние `detail`/`self`/`history` при ответе `FORBIDDEN` или `NOT_FOUND`. После первоначальной загрузки частного названия/кода приглашения/истории и последующего отзыва доступа явный refresh оставляет эти данные видимыми. Воспроизведение: fake repository сначала возвращает встречу с названием/кодом приглашения и историю, затем 403 на detail/history; refresh сохраняет прежние data. Добавляются регрессии очистки всей проекции данной встречи; сетевые ошибки должны по-прежнему сохранять кэш. Ни IDOR на сервере, ни утечка между аккаунтами этим наблюдением не утверждаются.
- **APP-003 / P2 / материалы individual_tracks:** `RecordingsViewModel.kt:openLibraryRecording` требует `playable`, хотя карточка режима `individual_tracks` предлагает «Материалы», а модель поддерживает готовую запись только с `tracks_archive`, без `final_mp4`/`final_audio`. Такая строка показывается в каталоге, но нажатие ничего не делает. Воспроизведение: global catalogue возвращает ready individual_tracks с доступным архивом; `actions.openLibrary` не открывает detail. Регрессия должна подтвердить открытие материалов без автоматического воспроизведения или выдачи media lease.

## Результаты исходного запуска и дополнительные наблюдения

Исходный Gradle-запуск завершился успешно за 1m46s: 238 app + 170 data + 28 media + 71 SSH = **507 passed**, 12 live data tests skipped. Debug lint: 57 warnings/1 hint; release: 56 warnings/1 hint; errors0. Python installer126 + verification16 passed. Собраны debug/test APK и minified unsigned release APK/AAB. Замороженная пара debug/test сохранена в `.audit/android-audit-20261027/baseline-apks` для проверки регрессий на старом коде.

Холодный процессный старт на одном API36 ARM64/4KiB AVD: 2 прогрева исключены, 7 измерений, median1639ms, min1504ms, max2020ms. Это `am start -W TotalTime` до первого кадра debug-приложения, не fully-drawn/реальное устройство; сырые значения в baseline-startup.json.

OSV exact-version API: 178 Maven coordinates из releaseRuntimeClasspath, 0 advisory matches, pagination complete. Не проверяет полноту нативных third-party исходников WebRTC или toolchain. Artifact verifier: упаковка debug/release/AAB прошла; все 64-bit PT_LOAD выровнены на16KiB, но GNU_RELRO конца у graphics.path и x86_64 WebRTC отличаются от16KiB. Это отдельное наблюдение, не доказанная runtime-несовместимость; нужен запуск на16KiB. Signing, private-material и manifest checks зафиксированы в baseline-artifact-verification.json.

**TEST-01, P2 качества проверок:** исходные instrumented Registration/Installer UI tests местами нажимают после `performTextInput`/`performScrollTo` до завершения platform IME/layout animation. Например source radio оказался y=-133..48px, callback plan/SMTP не вызвался; registration visibility assertion остался masked. Использовать уже существующий `settleFormEditorForClick`, сохранив настоящий pointer click и исходные assertions; повторить на том же baseline APK, чтобы отделить нестабильность harness от ошибки продукта. Подтверждение исправления будет в итоговом test report.

Исходные и исправленные результаты должны храниться раздельно. Следующие записи находок app/data/media добавляются участниками аудита до их production edits; разделы baseline-data-findings.md и baseline-media-findings.md входят в исходные доказательства аудита.

- **APP-004 / P2 / восстановление громкости вызова:** исходный `RingtoneVolume.close()` использует тот же `allowed()` что повышение. `IncomingCallRingtone` включает в него отсутствие внешней гарнитуры. Если после временного повышения громкости подключить наушники и завершить звонок, неизменённая повышенная громкость не восстанавливается. Подтверждение управления также записано в `docs/audit/baseline-media-findings.md` до исправления. Требуется разделить разрешение повышать громкость динамика и разрешение безопасно восстановить свой неизменённый уровень, сохранив приоритет silent/vibrate/DND и ручного изменения громкости. Добавляются pure JVM-тесты подключения гарнитуры, ручного изменения и смены звуковой политики.

- **APP-005 / P1 / поздний результат разрешений звонка:** `ui/CallUi.kt:92–114` обрабатывает результаты join/microphone/camera без сохранённого target и foreground/lifecycle-проверки; `:133` начинает MediaProjection после результата разрешения уведомлений без проверки исходной встречи. В отличие от PersonalCallHost, после background, смены встречи или аккаунта старый результат может включить устройство/запустить новый picker в текущем контексте. Исправление: одноразовая привязка запроса к auth scope/встрече/участнику/phase, отказ от позднего результата, invalidation device/join request на ON_STOP/dispose. MediaProjection picker может законно закрывать Activity целиком: его явный request сохраняется через STOP, но результат принимается только для того же target в STARTED foreground, без отложенного автостарта. Добавляются регрессионные проверки привязки/однократности/background и запрета переиспользовать ещё не завершённый launcher для нового target.
