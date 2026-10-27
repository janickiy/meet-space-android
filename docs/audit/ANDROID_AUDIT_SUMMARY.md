# MeetSpace Android — итог аудита, 27.10.2026

**Аудит завершён: исправлено8 подтверждённых ошибок.** Сборки, JVM/Python проверки, адресные UI-регрессии, реальные API/WSS/SAF, WebRTC/SFU/TURN, MediaProjection и offline/LeakCanary сценарии выполнены. Невыполненная аппаратная/FCM/VPS/ABI матрица и условия выпуска перечислены ниже. Исходные неуспешные прогоны сохранены и отделены от успешных повторов. Подробности — [baseline](ANDROID_AUDIT_BASELINE.md), [проверки](ANDROID_TEST_REPORT.md), [безопасность](ANDROID_SECURITY_AUDIT.md), [память](ANDROID_MEMORY_LEAK_AUDIT.md), [производительность](ANDROID_PERFORMANCE_REPORT.md).

## Область и сохранение предыдущей работы

Начальная точка: `bb806e0b75ec720a695347b27d5d22d98a15b9a8` плюс **37 уже изменённых/новых файлов** задач уведомлений и записей. Их SHA-256 и исходный patch сохранены до исправлений в игнорируемой локальной папке `.audit/android-audit-20261027/pre-audit-files.json` и `pre-audit.patch`. Поэтому полный diff относительно HEAD включает предыдущую работу и не равен объёму данного аудита.

Проверены Android UI/ViewModels, lifecycle/account scopes, файлы/SAF, уведомления, записи, WebRTC/MediaProjection, REST/WSS/session storage, SSH, подписанный установщик, зависимости и выпуск. Исправлялись подтверждённые ошибки управления ресурсами/состоянием; редизайн и новые функции не добавлялись. Боевые сервисы, серверы пользователя и Amnezia/VPN в рамках аудита не изменялись. Для интеграционных проверок используются отдельные локальные fixture-сервисы.

Версия приложения осталась **0.3.11-beta / versionCode 29**, min SDK26, target/compile36. Android signing configuration и ключи не заменялись. Обновлённый runner установщика переподписан существующим ключом после изменения его SHA-256; публичный trust root и закреплённая версия исходников backend не менялись. APK/AAB не публиковались в репозиторий, лендинг или Google Play в рамках аудита.

## Восемь адресных исправлений

| ID / приоритет | Подтверждённая проблема | Внесённое изменение и проверяемое поведение |
| --- | --- | --- |
| **MEDIA-001 / P1** | Отключение камеры/микрофона, выход и освобождение projection могли ждать завершения более раннего REST/SDP/presence-запроса в одном actor. Два gated-теста на исходном APK подтвердили задержку очистки. | Отдельная ограниченная очередь lifecycle и отменяемый child Job для обычной операции. Срочная очистка остаётся сериализованной; устаревшие enable/consent и direct-call действия привязаны к исходному target. Прерывание setup/SDP закрывает соединение и требует явного повтора. Стабильная screen share сохраняется при отключении только камеры/микрофона. Финальные13 media/permission регрессий прошли. |
| **APP-005 / P1** | Поздний результат разрешения микрофона/камеры/join либо уведомлений мог примениться уже после background или к новой встрече/аккаунту. | Одноразовые запросы привязаны к auth scope, встрече, direct-call ID, участнику и phase; перед применением проверяется актуальное engine state и STARTED foreground. ON_STOP инвалидирует device/join/notification запросы. Явный MediaProjection picker допускает STOP исходной Activity, но результат всё равно требует того же target и foreground. Добавлены 5 JVM и 3 теста настоящих CallHost launchers/lifecycle; обычный PAUSED permission dialog имеет положительный контроль. |
| **APP-001 / P2** | Отмена принятого SAF-экспорта до старта LAZY coroutine обходила catch и оставляла новый пустой документ. | Очистка связана с завершением принятого Job, включая отмену до старта и очистку ViewModel. Успешно записанный файл сохраняется; обработчик использует application ContentResolver. Регрессии проверяют back/clear, ровно одну очистку и успешную запись. |
| **APP-002 / P2** | Явный403/404 refresh встречи сохранял ранее загруженные частные подробности/историю/приглашение в UI. | Отозванная проекция очищается вместе с устаревшими дочерними запросами и карточкой данной встречи. Отказ только endpoint истории/членства очищает соответствующую проекцию, сохраняя доступную встречу. Сетевой сбой сохраняет разрешённый кэш. Это исправление stale cache; серверный IDOR этим наблюдением не установлен. |
| **APP-003 / P2** | Готовая запись `individual_tracks` только с архивом дорожек показывала «Материалы», но не открывалась из общего каталога из-за требования `playable`. | Разрешён переход к материалам ready/partial_ready с доступными файлами. Архив не запускается как аудио/видео; открытие деталей не выдаёт playback lease и не включает autoplay. Добавлена регрессия открытия и экспорта архива. |
| **APP-004 / P2** | Подключение гарнитуры во время вызова запрещало восстановление неизменённой временно повышенной громкости звонка. | Политика повышения через динамик отделена от безопасного восстановления собственного изменения. Silent/vibrate/DND и ручное изменение уровня сохраняют приоритет. Три JVM-регрессии покрывают гарнитуру, ручной уровень и смену звуковой политики. |
| **APP-006 / P2** |403/404 необязательного ресурса pins/search/context/pin/attachment ошибочно означал отказ в доступе ко всему чату: выбор, история и draft очищались. Реальный legacy fixture07 воспроизвёл pins404 при успешном detail/history. | Ошибка остаётся в соответствующей проекции; ViewModel повторно проверяет доступ через canonical conversation endpoint. Только его403/404 либо отказ основной истории очищает чат. Подтверждённо доступный чат и draft сохраняются; context возвращается к обычной истории. Четыре JVM-метода проверяют initial pins404, матрицы шести ресурсов×403/404 при canonical success/denial и временные network failures; все48 MessagingViewModel tests прошли. Реальные Android HTTPS/WSS сценарии после исправления —4/4 passed на прежнем fixture07. |
| **DATA-001 / P2** | Верификация backup целиком читала PostgreSQL dump в Python heap; размер выделения рос вместе с базой. | Chunked SHA-256 и stdin файлового дескриптора для `pg_restore --list`; cleanup, receipt, atomic publication и коды ошибок сохранены. Восемь новых локальных регрессий прошли; полный installer suite после переподписи —134 passed. В исходном data baseline эта же находка обозначена `DATA-01`. |

Непрерываемый native/vendor вызов невозможно сделать кооперативно отменяемым одной coroutine: работа lifecycle priority должна дополнительно проверяться на реальном оборудовании. При прерывании setup/SDP приложение выбирает полное закрытие и явный retry; для личного звонка существующий UI может требовать завершить его и начать заново.

## Доказательства и текущий статус проверок

| Проверка | Реальный результат / граница утверждения |
| --- | --- |
| Исходный Gradle |507 passed,12 live data skipped; debug/release lint errors0. Debug/test APK и minified unsigned release APK/AAB собраны. Сохранена неизменяемая исходная пара APK для регрессий. |
| Итоговый Gradle |Финальный после APP-006 `.audit/android-audit-20261027/final2-gradle.log`: **BUILD SUCCESSFUL in2m20s**,505 actionable tasks; **527 JVM passed,12 live data skipped**, failures/errors0. Debug/release lint errors0, warnings57/56 и hint1; собраны debug/test APK и minified unsigned release APK/AAB. Отдельные включённые live data проверки указаны ниже. |
| Исходные MEDIA-001 регрессии |Два gated-теста на исходном APK упали на ожидаемой проверке освобождения до завершения сетевого запроса; лог `baseline-media-priority.log`. Итоговые проверки actor/permission/projection отдельно фиксирует test report. |
| Приоритетное отключение/permission lifecycle |`final-media-priority.json`: **13/13 passed**, включая queued direct preview/новый call, сохранение idle screen share, старый projection callback, normal PAUSED grant и отклонение STOP/другой встречи. Это controlled actor/launcher тесты; аппаратные ограничения сохраняются. |
| Установщик Python |Исходно126 passed; после потокового backup134 passed. Verification suite исходно16 passed. Полная удалённая установка на disposable VPS в этом аудите **не выполнена**. |
| Реальные локальные REST/WSS/storage contracts |Stage05 —60 checks; Stage07 —460 итоговых checks, из них458 записаны до сериализации отчёта. Обновлённый fixture08 —**836 checks**, включая82 проверки global catalogue/ACL/cursor. Его версия отдельно зафиксирована: backend `f5267dcd025d21917512633ee92289531d90d18c` плюс9 существовавших recording-library source/test файлов с индивидуальными SHA-256; это реальный app/usecase/Postgres код, не mock API. Fresh disposable DB использует настоящие current migrations; старые fixtures сохранены для compatibility. |
| Live API/WSS JVM |`final-live-data.json`: **9/9 passed** — recordings4, messaging3, meetings2 против исходных локальных fixture05/07/08. Эти результаты получены до обновления fixture08 и не выдаются за доказательство его нового global route. |
| OSV exact-version |178 Maven coordinates,0 advisory matches, pagination complete. Проверка ограничена покрытием базы и не подтверждает отсутствие всех уязвимостей JNI/WebRTC/toolchain. |
| Локальный secret scan |1433 tracked/nonignored файлов до8MiB; найденные10 private-key headers относятся к валидации и синтетическим тестам. Это regex scan рабочего дерева, не всей Git history. |
|16KiB runtime |Native library load/Factory dispose smoke и2 offline lifecycle теста с включённым LeakCanary прошли на отдельном16KiB API36 ARM64 AVD. Это не полная функциональная матрица ABI/API/оборудования. ELF/ZIP alignment отдельно зафиксированы artifact verifier. |
| Реальные SFU/TURN и signaling |Свежий `final-live-call-refreshed.json`: `nativeCameraRelayReconnectAndCleanup` **passed** через настоящий TURN; прежний неуспешный запуск сохранён отдельно. `screenConsentBackgroundAndStop` и `rejectedIceClosesAlreadyOpenedSignaling` также **passed** в `final-live-call.json`. Это эмулятор и disposable Pion SFU/TURN, не физическая матрица. |
| Реальная MediaProjection/FPS |`final-screen-projection.json`: **1/1 passed**. Стандартный Android consent, production ScreenShareService/CallEngine, local native VP8 loopback: старт,2 поворота, остановка, свежий consent/restart; первая projection активна31.151s. Прирост outbound-rtp.framesEncoded даёт13.75–15.38fps в окнах5–9.53s, при capture21.73–35.37fps; после stop прирост0. Физическое high-refresh устройство и серверная запись сегментов не проверены этим тестом. |
| Live Android messaging |После APP-006 `final2-live-messaging.json`: **4/4 passed** на legacy fixture07 — direct send/reply/edit/delete/rotation, группы/папки/MinIO, offline/radio/WSS REST resync/logout и переход из настоящего foreground уведомления в inbox. |
| UI, recordings/player/SAF | LiveRecordingsTest4/4 PASS на актуальном fixture08; LiveMessagingSafTest1/1 PASS. UI: основной196-test запуск184PASS/3FAIL/9SKIP, исправления harness подтверждены на baseline и итоговом APK. Последние результаты случаев188PASS/8SKIP/0failures — агрегат повторов, не один зелёный полный запуск. История и4/4 финальных адресных теста в test report. |
| Activity/native lifecycle, LeakCanary |После правок **2/2 passed** на4KiB AVD (`final-offline-lifecycle.json`); отдельно **2/2 passed** в debug с LeakCanary на16KiB AVD (`final-leakcanary.json`). В каждом workload20 Activity cycles после3 warmup и100 Factory/EGL/audio-module cycles после10 warmup; наблюдаемые weak refs собраны. Workloads не создают network/capture session и не доказывают отсутствие всех C++/media утечек. |

Измеренный локальный hotspot установщика: для синтетического dump32MiB исходное выражение hash потребовало33,559,058B traced Python heap; исправленная полная backup phase с mocked Docker dump/restore —2,136,534B. Сравниваются разные объёмы работы: первая цифра только hash, вторая вся phase. Это доказательство ограниченного буферного расхода, не RSS или latency реального VPS.

Исходный debug cold process start на одном API36 ARM64 AVD:7 измерений после2 исключённых прогревов, median1639ms, min1504ms, max2020ms по `am start -W TotalTime`. Это время первого кадра в эмуляторе, не fully-drawn и не производительность физического телефона. Итоговые сопоставимые измерения и границы jank/CPU/battery — в [performance report](ANDROID_PERFORMANCE_REPORT.md).

## Какие файлы относятся к аудиту

Из37 существовавших до аудита файлов **32 совпадают с сохранёнными SHA-256** по итоговому сопоставлению с baseline snapshot. Пять дополнительно изменены адресными исправлениями:

- `app/src/main/java/com/meetspace/app/MessagingHost.kt` — application ContentResolver для cleanup экспорта.
- `app/src/main/java/com/meetspace/app/MessagingViewModel.kt` и `app/src/test/java/com/meetspace/app/MessagingViewModelTest.kt` — APP-001, APP-006 и регрессии.
- `app/src/main/java/com/meetspace/app/RecordingsViewModel.kt` и `app/src/test/java/com/meetspace/app/RecordingsViewModelTest.kt` — APP-003 и регрессия.

Глобальный каталог записей, обложки, notification center, маршрутизация и звуковое сопровождение уведомлений уже входят в37-file snapshot. Их наличие в полном diff не считается новым исправлением аудита. Точный полный перечень и хэши исходных37 файлов сохранены в `pre-audit-files.json`.

Файлы рабочего кода, изменённые непосредственно аудитом сверх этого snapshot:

- `app/src/main/java/com/meetspace/app/CallDiscussionViewModel.kt`, `MeetingCallHost.kt` — APP-001; `MeetingsViewModel.kt` — APP-002.
- `app/src/main/java/com/meetspace/app/RingtoneVolume.kt`, `IncomingCallRingtone.kt` — APP-004.
- `app/src/main/java/com/meetspace/app/CallPermissionRequest.kt`, `ui/CallUi.kt` и интеграция в `MeetingCallHost.kt` — APP-005.
- `core/media/src/main/kotlin/com/meetspace/core/media/CallEngine.kt` — MEDIA-001.
- `installer/v1/runner.py`, `release.json`, `release.sig` — DATA-001 и подпись обновлённого runner существующим ключом.

Регрессионные и измерительные файлы аудита:

- JVM: `app/src/test/java/com/meetspace/app/CallDiscussionViewModelTest.kt`, `MeetingsViewModelTest.kt`, `RingtoneVolumeTest.kt`, `CallPermissionRequestTest.kt`; два уже существовавших изменённых ViewModel-теста указаны выше.
- Instrumentation: `app/src/androidTest/java/com/meetspace/app/CallLifecyclePriorityTest.kt`, `CallPermissionLifecycleTest.kt`, `AuditOfflineLifecycleTest.kt`; актуализация harness `ScreenShareProjectionTest.kt`, `LiveRecordingsTest.kt`, `LiveMessagingTest.kt`, `LiveMessagingSafTest.kt`, `RegistrationUiTest.kt`, `InstallerUiTest.kt`, `MessagingUiTest.kt`, `RecordingsUiTest.kt`, `FormEditorSynchronization.kt`.
- Python: `installer/tests/test_backup_streaming.py`.
- CI: `.github/workflows/android-quality.yml` расширяет explicit offline test allowlist и проверку звука; private live fixtures в CI не добавляются.
- Отчёты: этот summary, baseline, security, memory, performance, test report, `baseline-data-findings.md`, `baseline-media-findings.md`, безопасная сводка `evidence/osv-summary.json`.

Сырые журналы, APK snapshots и приватные рабочие материалы остаются в игнорируемой локальной `.audit`. Одноразовые fixture databases/containers/credentials и оба созданных AVD удалены после проверки; существующие пользовательские AVD, ключи и серверы не затронуты. Безопасные JSON доказательства лежат в [evidence](evidence).

## Совместимость и условия выпуска

REST/WSS/media payload и модель прав backend не менялись. Очистка клиентского кэша не заменяет серверную авторизацию. TLS/DTLS-SRTP обеспечивает защиту транспорта и не обозначается как end-to-end encryption от SFU. Публичный ключ и схема проверки подписи установщика сохранены.

До утверждения готовности релиза остаются отдельные условия:

1. Провести единую полную CI/device серию на целевой матрице перед публикацией. Локальные регрессии завершены; успешная сборка отдельно не подтверждает качество звука, все OEM lifecycle особенности или FCM.
2. Перед публикацией передать постоянный Android signing key через существующие четыре env-переменные и проверить сертификат ранее распространявшегося APK. Сейчас release-подпись окружением не задана; unsigned release не является готовым совместимым обновлением.
3. Настроить Firebase и backend push gateway для проверки background/terminated FCM. Foreground WSS и локальный notification channel этого не подтверждают.
4. Выполнить физическую device matrix: камера/микрофон, слышимость, Bluetooth/гарнитуры/audio focus, высокая частота экрана и запись≥30s, несколько границ сегментов, сеть Wi-Fi/mobile, split-screen, permission revocation. Эмулятор не заменяет эти проверки.
5. Проверить полный удалённый install/upgrade/recovery/restore на disposable Ubuntu22.04/24.04 VPS с заявленными ресурсами; не использовать рабочий сервер пользователя как тестовый стенд.
6. Ввести Gradle dependency verification после независимой проверки эталонных checksum. Сейчас отдельного `verification-metadata.xml` нет;0 совпадений OSV не является проверкой целостности Maven cache или всего supply chain.

Подтверждённых неисправленных P0/P1 в исследованной области не осталось. Это не гарантия отсутствия неизвестных уязвимостей. **Сборка пригодна для дальнейшей проверки, но не объявлена готовым релизом для публикации**: постоянная подпись, FCM, физические устройства и полный disposable VPS drill остаются условиями выпуска. Подробные выводы по безопасности и остаточным рискам — в [security report](ANDROID_SECURITY_AUDIT.md), по владению ресурсами и измерениям — в [memory report](ANDROID_MEMORY_LEAK_AUDIT.md).

Финальные показатели: quiet20Activity cycles —0/276 retained, FD120→118;100factory cycles —0/330, FD122→122. Cold process first-display p50/p95:1639/2020→960/1098ms,n7; after warm128/150ms,n7. Целевого ускорения запуска не было: JIT/cache/история нагрузки различались, причинный performance gain не заявляется. Итоговый minified APK с временной тестовой подписью запущен на16KiB API36 ARM64 с выключенной compat mode (`final2-release16k-smoke.json`).

Артефакты проверки: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release-unsigned.apk`, `app/build/outputs/bundle/release/app-release.aab`; абсолютный корень `/Users/aleksandranickij/htdocs/Android`. Версия и ключи подписи Android не менялись.
