# Этап 07 — проверка чатов, папок, файлов и уведомлений

## Фактические результаты

| Проверка | Результат | Доказательство |
|---|---:|---|
| Настоящие Gin + PostgreSQL + Redis + MinIO + HTTPS/WSS | 458 assertions passed | [backend-contract.json](evidence/07/backend-contract.json) |
| Native repository stage07 live | 3 passed, 0 skips | [live-repository.xml](evidence/07/live-repository.xml) |
| MessagingRepository unit | 22 passed | [messaging-repository-unit.xml](evidence/07/messaging-repository-unit.xml) |
| Общая JVM-регрессия | 229 tests: 227 passed, 2 opt-in skips прежних этапов, 0 failures/errors | [jvm-summary.json](evidence/07/jvm-summary.json) |
| Compose UI | 59 уникальных: прежние 40 + messaging 19; последние 19 повторно прошли | [исходный suite](evidence/07/tablet-ui.txt), [финальные messaging + SAF + OS](evidence/07/final-android.txt) |
| Реальный Android E2E | 5 уникальных сценариев прошли: direct/rotation; group/files/folders/inbox; offline/reconnect/logout; системный SAF; OS notification | [первые три](evidence/07/live-android.txt), [SAF/OS + UI](evidence/07/final-android.txt) |
| Телефон 1080×1920 / 480dpi / font150%, показ экранной клавиатуры разрешён | 2 passed: UI + настоящий direct/rotation | [phone-live-font150.txt](evidence/07/phone-live-font150.txt) |
| Сборки / Lint | debug, AndroidTest, minified unsigned release; 0 ошибок, 43 предупреждения | [final-build.txt](evidence/07/final-build.txt) |
| APK / изоляция / cleanup | Статические проверки прошли; 16 KiB runtime не проверен; private inputs удалены, 0 fixture sockets/containers | [apk-verification.json](evidence/07/apk-verification.json), [cleanup.json](evidence/07/cleanup.json) |

458 — число assertions, включая проверки HTTP-статуса и структуры ответа; это не 458 независимых пользовательских сценариев. Реальные сценарии включают direct/group, повтор сообщений и conflict, reply/edit/delete/read, роли/передачу владения/выход, три аккаунта и два устройства одного аккаунта, REST resync после WSS reconnect, IDOR, отзыв доступа к групповым файлам/истории/событиям, подпись и приватность MinIO, папки с встречами и чатами, календарные приглашения, read уведомлений. Production не изменялся.

После закрытия всех probe-сокетов серверный snapshot показывал `userWebSocketConnections=0`. Это подтверждает освобождение соединений стенда; измерение heap/leak-profiler Android в это утверждение не входит.

## Воспроизводимый стенд

Подробные требования, безопасный запуск, передача CA/учётных данных и cleanup описаны в [testing/stage07/README.md](../../testing/stage07/README.md). Используется commit `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`; fixture создаёт собственные PostgreSQL/Redis/MinIO и собирает немодифицированные business handlers/repositories. Тестовый `main.go` только связывает эти компоненты и добавляет TLS termination. Redis/MinIO имеют отдельные container ID и ownership labels. Никакие существующие контейнеры `go-recorder` не останавливаются.

```sh
python3 testing/stage07/fixture.py start --backend-source .audit/meet-space-stage04
python3 testing/stage07/run_contract.py
MEETSPACE_STAGE07_LIVE=1 \
MEETSPACE_STAGE07_FIXTURE="$PWD/.audit/stage07-fixture/live.properties" \
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" \
./gradlew :core:data:testDebugUnitTest --tests '*LiveMessagingRepositoryTest'
```

Без `MEETSPACE_STAGE07_LIVE=1` JVM-интеграция пропускается осознанно. При включённом флаге Gradle не считает прошлый тестовый вывод достаточным и запускает тест снова. Не запускать одновременно несколько Gradle test tasks одного модуля: они перезаписывают общий JUnit output.

Private home `.audit/stage07-fixture` — 0700; credentials, ключи, SQL и настройки не копируются в source/assets/report. API на HTTPS 18477, подписанные S3 ссылки на HTTPS 19477 с тем же CA и реальным host SAN. Android использует API `10.0.2.2`, JVM — `127.0.0.1`; signed S3 host достижим с обоих. TLS hostname verification остаётся стандартной. Unsigned MinIO object GET действительно возвращает 403.

## Android live E2E

Runner: `com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner`.

В instrumentation APK находится `LiveMessagingBackend`: reflection вызывает существующие constructors внутренних `DefaultFoundationRepository`/`MeetSpaceApi`. Это даёт настоящий repository клиент, строгий fixture CA и RAM stores без расширения production API и без mock business responses. `LiveCallApplication` выбирает его только при наличии private `files/live-messaging.properties`; исходный callEngine не менялся. До запуска нужно завершить предыдущий процесс приложения и скопировать properties/CA через stdin по инструкции README. Stage06 `live-call.properties` должен отсутствовать.

```sh
adb shell am instrument -w \
  -e class com.meetspace.app.LiveMessagingTest \
  com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
```

Для выборочного повтора после конкретного исправления:

```sh
adb shell am instrument -w \
  -e class 'com.meetspace.app.LiveMessagingTest#realDirectMessagesReplyEditDeleteAndRotation' \
  com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
```

Методы:

1. `realDirectMessagesReplyEditDeleteAndRotation`: MainActivity → UI composer → ViewModel → реальный сервер; отправка, ответ, изменение, tombstone удаления, подтверждение вторым реальным repository, recreation/поворот с сохранением transient draft.
2. `realGroupFoldersMinioFilesAndNotificationCenter`: реальная группа/состав, upload/finalize/send и authenticated group download с совпадением SHA-256/байтов, папка с чатом и встречей, фактическое уведомление и его read acknowledgment.
3. `realOfflineHistoryReconnectAndLogoutCleanup`: временно отключает Wi-Fi и mobile data эмулятора, восстанавливая прежние значения в `finally`; проверяет RAM history при ошибке refresh, реальный WSS reconnect, REST восстановление сообщения, отправленного вторым клиентом при остановленном stream, очистку истории/черновика/вложений при logout.
4. `realForegroundWssNotificationOpensInboxAndClearsOnLogout`: другой настоящий клиент отправляет direct message; foreground WSS вызывает системное уведомление с tag `meetspace-messaging`/id `707`, generic text и private visibility. Проверяет отсутствие raw message body в extras, настоящий PendingIntent → MainActivity → inbox, затем очистку OS notification при logout. Метод сохраняет исходное разрешение `POST_NOTIFICATIONS`; для отдельного запуска при запрещённом разрешении лучше выдавать его через adb до instrumentation и восстанавливать после получения результата, поскольку Android может завершать процесс при отзыве разрешения.

Тест 2 передаёт контролируемые байты production ViewModel `upload`/`export` и использует настоящий MinIO. Отдельный `LiveMessagingSafTest` действительно прошёл OpenDocument → загрузку/отправку → CreateDocument → сохранение с совпадением SHA-256. Строка SAF export не была доступна тому же UID через MediaStore, поэтому независимый test-only observer проверил точный UUID-файл через shell; разрешения приложения не расширялись. Созданные файлы удалены. Приватные свидетельства в app cache: `stage07-live-direct.png`, `stage07-live-group-folder.png`, `stage07-live-notifications.png`, `stage07-live-offline.png`, `stage07-live-evidence.txt`, `stage07-progress.txt`. Они содержат только одноразовые fixture данные; перед публикацией screenshots просматриваются.

## Регрессия гонки истории

В `MessagingViewModelTest` добавлены два сценария с задержанным GET, который игнорирует cancellation до получения ответа: старый snapshot не должен удалять подтверждённое отправленное сообщение, а поздняя ошибка GET — возвращать старую версию уже изменённого сообщения. Send/edit acknowledgment отменяет незавершённый history request до применения подтверждённой строки. Историю удаления обновляет новый GET с отменой прежнего запроса. Обе регрессии прошли в финальном JVM run. Дополнительная регрессия на 350 сообщений проверяет скользящее окно ≤200, непрерывный cursor, сохранение версий и отсутствие старых read markers при чтении предыдущих страниц.

Отдельная регрессия `olderPaginationMovesPastTwoHundredMessagesWithoutGrowingMemoryOrLosingCursor` проверяет семь страниц истории из 350 сообщений. При запросе старых сообщений окно сдвигается назад и остаётся ограниченным 200 строками, курсор не теряется на этой границе, stale overlap не понижает известную версию сообщения. Fresh REST возвращает последнюю страницу. Итог запуска этой новой регрессии заполняется после финальной JVM-проверки.

## Ограничения и завершение

Fixture интеграции использует фактическую capability `push=noop`: регистрация Android device корректно возвращает 503, доставки FCM не имитируются. Проверки Firebase SDK, gateway и фоновой доставки требуют отдельно настроенного приложения и провайдера. Наличие device REST API само по себе не подтверждает push delivery.

Offline история хранится в ограниченном RAM-кэше текущего account/origin и не является постоянным хранилищем после перезапуска процесса. Group bytes защищены текущим membership; подписанные direct ссылки могут действовать до своего срока, уже экспортированные пользователем файлы отозвать невозможно.

После окончания всех потребителей force-stop приложения, удалить только private fixture inputs из его files/cache, затем:

```sh
python3 testing/stage07/fixture.py stop --remove
```

Проверить освобождение портов 15477/16377/18477/19077/19477 и отсутствие только owned fixture containers. Секретные файлы не должны остаться в APK, отчётах или архивируемых evidence.


## Исправления по результатам реальных прогонов

Первоначальные ошибки SAF-теста относились к обновлению узлов DocumentsUI, видимости MediaStore и shell quoting в UiAutomation; исправленный сценарий с настоящими диалогами и байтами прошёл. PendingIntent меняет Activity intent, поэтому OS notification тест отслеживает lifecycle напрямую вместо неподходящего закрытия ActivityScenario.

На телефоне при повороте и font150% найдено реальное исчезновение высоты истории, затем ошибка Compose при смене направления прокрутки поля. Компоновка теперь сокращает заголовки, переносит дополнительные действия в доступный диалог и оставляет поле с вертикальной прокруткой. Тест 280dp требует history≥72dp; переход 540→280→540dp сохраняет фокус и черновик. Финальный телефонный прогон также прошёл с разрешённым показом экранной клавиатуры.

Все owned fixture resources удалены, 11 ранее запущенных контейнеров сохранены; настройки дисплея, шрифта, IME и сети эмулятора восстановлены. Машиночитаемый итог: [07_VERIFICATION.json](07_VERIFICATION.json).
