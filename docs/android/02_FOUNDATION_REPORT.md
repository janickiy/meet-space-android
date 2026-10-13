# Этап 02 — Android-каркас и авторизация

Дата: 2026-10-11. Этап 02 завершён: приложение собрано и проверено на Android-эмуляторе с реальным backend. Основание и предварительные gaps: [02_FOUNDATION_PLAN.md](02_FOUNDATION_PLAN.md). Следующие этапы не выполнялись.

## Реализовано

- Проект Kotlin/Jetpack Compose/Material 3 с четырьмя модулями, minSdk 26, target/compileSdk 36. Версии зависимостей зафиксированы. APK не содержит выбранного production URL или учётных данных.
- Первый запуск: подключение к существующему серверу и информационный экран собственного сервера.
- Строгая нормализация корневого HTTPS origin, системная проверка TLS, публичный `/version` до отправки пароля, затем login, me и защищённый capabilities. Редиректы с учётными данными не выполняются; сообщения об ошибках не содержат ответы HTTP или секреты.
- Серверные профили и настройки темы в DataStore. Один запоминаемый аккаунт на origin; переключение сервера очищает активную память; переключение аккаунта удаляет прежний локальный секрет. Сессии привязаны к точному origin и ID аккаунта.
- Access token в памяти; durable `meetrix_session` cookie в AES-GCM ciphertext с ключом Android Keystore, в приватной папке без резервного копирования. Пароль не сохраняется. Refresh восстанавливает вход после перезапуска; безопасный GET допускает один refresh на 401, без повторов на 403.
- Logout отзывает удалённую сессию, стирает локальную и отдельно сообщает, когда сервер не подтвердил отзыв. Ошибки хранилища обрабатываются; они не представляются успешным удалением.
- Токены первой версии дизайна, нативный логотип, светлая/тёмная/системная темы. Четыре вкладки, нижняя навигация (<600dp), rail (600–839dp), постоянный drawer (>=840dp). Скролл, минимальные touch targets 48dp, подписи, семантика выбора/заголовков, озвучивание ошибок. URL/email/вкладка переживают пересоздание; пароль очищается.

## Файлы

- Корень: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle Wrapper, `.gitignore`, `README.md`.
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`; в `app/src/main/java/com/meetspace/app/`: `MainActivity.kt`, `MeetSpaceApplication.kt`, `FoundationViewModel.kt`, `ui/FoundationUi.kt`.
- Ресурсы `app/src/main/res/`: логотип `drawable/ic_meetspace.xml`, `values/strings.xml`, `values/styles.xml`, явное исключение всех данных из backup/переноса устройства в `xml/backup_rules.xml` и `xml/data_extraction_rules.xml`.
- `core/model/src/main/kotlin/com/meetspace/core/model/FoundationModels.kt`.
- В `core/data/src/main/kotlin/com/meetspace/core/data/`: `FoundationRepository.kt`, `DefaultFoundationRepository.kt`, `MeetSpaceApi.kt`, `ServerOrigin.kt`, `SessionCookieJar.kt`, `FoundationStores.kt`.
- `core/designsystem/src/main/java/com/meetspace/core/designsystem/MeetSpaceTheme.kt`; Gradle scripts каждого модуля.
- В `core/data/src/test/kotlin/com/meetspace/core/data/`: `FoundationRepositoryTest.kt`, `ServerOriginTest.kt`, `SessionCookieJarTest.kt`; `app/src/test/java/com/meetspace/app/FoundationViewModelTest.kt`.
- В `app/src/androidTest/java/com/meetspace/app/`: `FoundationUiTest.kt`, opt-in `LiveAuthTest.kt` с временным приватным файлом ввода.
- Документы `02_FOUNDATION_PLAN.md`, этот отчёт, [02_VERIFICATION.json](02_VERIFICATION.json) с количеством тестов и SHA-256 APK; изображения в `screenshots/`.

## Проверки

### Реальный backend

Реальный HTTPS backend `https://meeting.janickiy.com/`:

- `/version`: `v1.0.0-meeting.20261010-notifications.2`, commit `096c17b08352109f3f0b4d9f88d8e3825fd9e5fa`, buildTime `2026-10-10T12:24:32Z`.
- Login, `/auth/me`, `/capabilities`, refresh и logout: success; аккаунт после refresh совпадает; Secure durable cookie присутствует.
- Capabilities: liveCaptions/transcription/aiSummary/semanticSearch/meetingAnalytics=false; recordingModes=composite/audio_only/individual_tracks/screen_focus. Флаги показываются как свойства сервера; функции Android следующих этапов не выдаются за реализованные.
- Первая попытка входа получила 401; пользователь уточнил пароль, следующая полная проверка прошла. Пароль/токены не включены в отчёт.

### Сборка, тесты и эмулятор

Установлены JDK 17.0.20.1, SDK Platform 36, Build Tools 35.0.0, Emulator 37.2.12 и AOSP ARM64 API 36. Использован планшетный AVD `MeetSpace_Tablet_API_36` (2560×1600, 320dpi; 1280×800dp в landscape). Wrapper Gradle 8.13 проверяет SHA-256 дистрибутива; AGP 8.13.2 и Kotlin 2.2.21 зафиксированы.

| Проверка | Фактический результат |
| --- | --- |
| `:app:assembleDebug`, `:app:assembleDebugAndroidTest` | Успех; оба APK собраны |
| `:core:data:testDebugUnitTest` | 29/29; 0 failures/errors |
| `:app:testDebugUnitTest` | 7/7; 0 failures/errors |
| `:app:lintDebug` | 0 errors, 23 warnings о доступных новых версиях зависимостей |
| `FoundationUiTest` на API 36 | 10/10; onboarding, server profiles, навигация по ширине, темы, пароль и пересоздание Activity |
| `LiveAuthTest#connectLoginAndRotate` | 1/1; ввод URL, проверка сервера, реальный вход, account/capabilities, обе ориентации, сохранение вкладки и темы |
| `LiveAuthTest#restoreAfterProcessRestartAndLogout` в отдельном процессе после force-stop | 1/1; refresh восстанавливает аккаунт и тёмную тему; logout подтверждён сервером, UI возвращается к входу |
| Локальное удаление после выхода | Папка зашифрованных сессий пуста; временный файл учётных данных удалён с эмулятора и компьютера |
| Визуальная проверка | Планшет portrait/landscape, телефонный viewport 1080×2400 при 440dpi (~393×873dp), масштаб текста 200%; форма доступна через прокрутку |
| Проверка всех файлов внутри debug APK | Production origin и email тестового аккаунта отсутствуют; адрес вводится пользователем |

JVM suite проверяет изоляцию origin/аккаунтов, refresh после перезапуска, единичный refresh на 401, отсутствие повторов на 403, отказ недоверенного TLS, запрет переноса credentials через redirect, ошибки DataStore/Keystore, logout, конкурентные действия и отмену медленного HTTP body. ViewModel suite также проверяет offline startup, истёкшую сессию, повторные нажатия и сбой сохранения темы при действующем входе.

При первом прогоне исправлены импорт тестового API Compose и неверное ожидание исчезновения password-семантики при показе пароля: проверка теперь сравнивает фактическое маскирование текста. После исправлений suites полностью прошли. Предупреждение Lint о data extraction rules устранено явными backup exclusions. Нерешённых ошибок сборки/пройденных suites нет; предупреждения об обновлениях зависимостей сохранены для отдельного обновления совместимого набора.

APK: [`../../app/build/outputs/apk/debug/app-debug.apk`](../../app/build/outputs/apk/debug/app-debug.apk), 20 241 396 байт (~19,3 MiB), SHA-256 `cce18f968a5690d84739ab5129efcb1230a8d2c771e3b9497bb60f15a99cbe0e`. Это debug-сборка для проверки, а не подписанный release для публикации.

Скриншоты фактического приложения:

- [Планшет, landscape, тёмная тема](screenshots/tablet-landscape-dark.png).
- [Планшет, portrait, тёмная тема](screenshots/tablet-portrait-dark.png).
- [Телефон, вход](screenshots/phone-login.png).
- [Телефон, текст 200%](screenshots/phone-login-large-text.png), [нижняя часть после прокрутки](screenshots/phone-login-large-text-scrolled.png).

Повторяемые команды сборки и opt-in сценарий реального входа описаны в [README](../../README.md). Не запускались: release publication, тесты на физических устройствах, ручная проверка TalkBack и настоящий split-screen. Изменение доступной ширины проверено Compose suite и телефонным viewport; это не заменяет проверку split-screen на устройстве.

## Риски и ограничения

- Commit production не совпадает с просмотренным checkout backend. Реальный auth-контракт подтверждён отдельно; это не подтверждение совместимости будущих REST/WS/WebRTC функций.
- Сервер с недоверенным/просроченным сертификатом будет отклонён. Private CA установка/настройка не добавлена; TLS bypass отсутствует.
- За один профиль сохраняется один аккаунт. Одновременные аккаунты одного origin и многопользовательская защита UI не реализованы.
- Access token не сохраняется; при восстановлении требуется доступ к серверу. При потере сети вход заново не считается успешным. Обработка изменений сроков cookie и её ротации предусмотрена.
- Серверный logout при недоступном сервере невозможно гарантировать. Локальный секрет удаляется, профиль аккаунта отвязывается; сообщение явно указывает отсутствие подтверждения сервера.
- UI ориентирован на русский язык. TalkBack и разные производители устройств требуют ручной проверки; наличие семантики и touch targets не заменяет такой проверки.
- Размеры 7–13 дюймов и multiwindow поддерживаются адаптацией к ширине окна; фактическая проверка пока ограничена планшетным эмулятором и изменением его viewport. Физические 7/13-дюймовые устройства и split-screen остаются в матрице ручной проверки.
- Lint сообщает о новых версиях AGP/библиотек. Текущий зафиксированный набор собран и проверен; обновление зависимостей следует делать отдельным изменением с повторением этих проверок.
- Звонки, чат, WebRTC, разрешения камеры/микрофона, notifications и SSH-установка не входят в этап 02. Реальных встреч/сообщений эта проверка не создаёт.

## Следующий этап

Остановиться после этапа 02. После отдельного запроса перейти к следующему приложенному промпту. Использовать существующий FoundationRepository и verified origin; не дублировать авторизацию и не добавлять секреты в навигацию/логи. Для медиа сначала разрешить выявленное в аудите несоответствие ICETransportPolicy/ICEExpiresAt и подтвердить реальный WS/TURN контракт. Проверки на следующих этапах должны работать с выделенным тестовым аккаунтом и реальными server capabilities.

## Источники совместимости инструментов

- [AGP 8.13: Gradle 8.13, JDK 17, SDK compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
- [Compose compiler Gradle plugin для Kotlin 2+](https://developer.android.com/jetpack/androidx/releases/compose-kotlin).
- [Android 16 adaptive layouts и игнорирование ограничений ориентации на >=600dp](https://developer.android.com/about/versions/16/behavior-changes-16#large-screens). Реальная проверка вращает устройство через UiDevice, а не рассчитывает на setRequestedOrientation.
