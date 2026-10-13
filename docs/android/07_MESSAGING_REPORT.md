# Этап 07 — чаты, папки, файлы и уведомления

Android **0.7.0**, versionCode **6**. Реализован этап из прямого запроса пользователя и приложенного документа 07. Машиночитаемый итог: [07_VERIFICATION.json](07_VERIFICATION.json). План до разработки: [07_MESSAGING_PLAN.md](07_MESSAGING_PLAN.md). Фактический API: [07_API_CONTRACT.md](07_API_CONTRACT.md). Backend закреплён на `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`; production и рабочий checkout backend не менялись.

## Реализовано

- Пятая вкладка «Чаты»: личные диалоги и группы, поиск пользователей/чатов, фильтры и cursor pagination. Широкое окно показывает список и переписку рядом; узкое или крупный шрифт — один экран с переходом назад. Используются цвета, типографика и компоненты первой версии дизайна MeetSpace.
- Отправка, ответы, правка и удаление собственных сообщений, tombstone, read/unread и доступный на сервере presence. Окончательные полномочия определяет backend. Меню не обещает администратору права править чужое сообщение.
- Создание и редактирование групп, просмотр состава, добавление/удаление участников, выход обычного участника, удаление группы владельцем. API смены ролей и передачи владения реализован в data layer; отдельного управления этими действиями в интерфейсе этой версии нет.
- Глобальный пользовательский WSS работает при активном приложении. На каждое соединение выдаётся свежий одноразовый ticket; после разрыва выполняются backoff, reconnect и REST-сверка. События служат сигналом обновления, не заменяют серверную проверку доступа. Удаление членства очищает доступную в памяти историю.
- Повтор после неопределённого результата отправки сохраняет исходный `clientRequestId`, текст и вложения. До подтверждения пользователь видит возможность повторить исходный запрос. Двойные нажатия блокируются; история объединяется по ID/версии, запоздавший GET не откатывает подтверждённую отправку или правку.
- Личные папки: создание, переименование, удаление, порядок, поиск и состав из чатов и встреч. Удаление элемента из папки не удаляет исходный ресурс; папка не даёт доступа к чужой встрече/переписке.
- Системный выбор и сохранение файлов через SAF; JPEG/PNG/WebP/PDF/TXT/CSV, максимум 5 вложений по 10 MiB. Реальные init → upload → finalize → attach; download заново проверяет доступ и SHA-256. Для private group content используется API, для подписанного direct URL — отдельный HTTPS-запрос без Bearer/cookies. Разрешения на весь накопитель не запрашиваются.
- Встроенный центр уведомлений, календарные приглашения/напоминания, прочтение и категории настроек. Системные уведомления активного приложения содержат общий текст без содержимого переписки. Нажатие открывает центр уведомлений.

## Данные и изоляция

История хранится только в ограниченном RAM-кэше: до 10 чатов и скользящее окно до 200 сообщений на чат; можно продолжать загружать более старые страницы за этой границей; текущие списки ограничены 300 элементами. История доступна при кратковременной потере связи; после завершения процесса её нужно загрузить заново. Нет постоянной offline-базы и автоматической очереди отправки. Черновик переживает пересоздание Activity вместе с ViewModel, но не записывается в saved state.

Смена HTTPS origin/аккаунта и logout отменяют операции, закрывают WSS, очищают RAM-историю, черновики, вложения и уведомления. Поздние результаты защищены поколением сессии и идентификатором запроса. Буферы файлов очищаются после использования; незавершённый новый SAF export удаляется при ошибке/отмене. Экспортированные по выбору пользователя файлы остаются у пользователя.

Production transport сохраняет обычную TLS/hostname verification и запрет redirects. Fixture CA и disposable credentials передавались только через закрытые тестовые файлы; reflection bridge и тестовый Application находятся в instrumentation APK. Системное доверие Android не менялось. Backend fixture запускает настоящие Gin handlers, PostgreSQL, Redis и private MinIO; business responses не подменяются.

## Push и конфигурация

Пользователь уточнил, что Firebase и push-шлюз **пока не настроены**. Сборка работает без `google-services.json`, фоновая доставка выключена и не обозначается успешной. Подготовлены закреплённый Firebase SDK, явное включение после разрешения Android, scoped device registration, token rotation, отзыв перед сменой аккаунта и WorkManager для REST-проверки входящих data-only hints.

Включение допускается только при реальной capability `push=http`; `noop`/`mock` отклоняются. Автоматический показ notification payload и делегирование Google Play Services выключены. FCM-токен не попадает в прикладные preferences/логи. Код компилируется, разбор hints покрыт пятью unit-тестами; настоящая доставка и настроенная Firebase-сборка ещё не проверены. Инструкции: [07_FCM_SETUP.md](07_FCM_SETUP.md).

## Фактические проверки

| Проверка | Результат |
|---|---|
| Реальный backend contract | 458 assertions passed: права/IDOR, idempotency, несколько клиентов, WSS reconnect, group membership, private MinIO, папки и уведомления |
| JVM: app 75, core:data 83, core:ssh 57, core:media 14 | 229 тестов: **227 passed**, 0 failures/errors, 2 opt-in live теста прежних этапов пропущены; все 3 stage07 live repository теста включены и прошли |
| Compose UI, API 36 ARM64, 2560×1600 / 320 dpi | **59 уникальных UI-тестов passed**: 40 прежних и 19 messaging; после последних изменений messaging 19/19 повторно прошли |
| Android → production repository → настоящий backend | **5 уникальных сценариев passed**: direct send/reply/edit/delete + rotation; group/files/folders/inbox; потеря сети/WSS resync/logout; настоящий SAF upload/download; WSS → Android notification → inbox/logout |
| Телефон, font150%, IME, portrait/landscape | **2 passed**: UI и реальный direct/reply/edit/delete/rotation; черновик и фокус сохранены |
| Debug / AndroidTest / unsigned minified Release | Собраны успешно |
| Lint | 0 ошибок; 43 предупреждения: обновления зависимостей/инструментов, style suggestions, синхронный SharedPreferences commit для сброса push binding |

SAF проверен через настоящие OpenDocument/CreateDocument: отправленные и сохранённые байты совпали по SHA-256; временные файлы удалены. Для независимой проверки сохранённого SAF export использован shell observer по точному UUID-имени: MediaStore не открывает его строку тому же UID автоматически. Это не расширяет разрешения приложения.

Проверка низкого окна 280dp при шрифте 150% подтверждает историю высотой не менее 72dp, доступность пяти вложений и ответа, сохранение черновика и фокуса при изменении высоты. В compact режиме ограничено число видимых строк поля, направление его прокрутки не переключается.

Статические проверки APK: debug v2 signature, ZIP alignment 16 KiB, PT_LOAD arm64/x86_64, пять исходных installer assets и лицензии WebRTC — прошли. Известные private fixture данные и тестовые файлы в основном APK отсутствуют; fallback Firebase service удалён, собственный FCM service выключен. [Полный APK-протокол](evidence/07/apk-verification.json) отдельно сохраняет GNU_RELRO findings для пяти native библиотек. Исполнение на устройстве с 16 KiB страницами не проверялось; этот аппаратный пункт нужен перед публикацией. Release остаётся unsigned.

Воспроизведение и evidence: [07_MESSAGING_TESTING.md](07_MESSAGING_TESTING.md).

## Изменённые файлы

- `core/model/.../MessagingModels.kt`: общие модели сообщений, групп, файлов, папок, уведомлений.
- `core/data/.../{MessagingRepository,MessagingApi,ScopedUserEvents}.kt`, `DefaultFoundationRepository.kt`, `MeetSpaceApi.kt`: контракт, scoped REST/WSS, авторизация и файлы; repository/unit/live tests и Gradle opt-in.
- `app/.../{MessagingViewModel,MessagingHost,MessagingFiles,MessagingNotifications}.kt`, `ui/{MessagingUi,MessagingUiModels}.kt`: state/effects, адаптивный интерфейс, SAF и уведомления.
- `app/.../{PushController,PushHint,MeetSpaceMessagingService}.kt`, manifest/build config: необязательный FCM и WorkManager; данные проекта Firebase не добавлены.
- `MainActivity.kt`, `FoundationViewModel.kt`, `MeetSpaceApplication.kt`, `FoundationUi.kt`, `MeetingsUi.kt`, `MeetingsViewModel.kt`: вкладка, lifecycle, auth cleanup и связь со встречами.
- App JVM/Compose/live/SAF tests, test-only `LiveMessagingBackend`, расширение `LiveCallApplication`; `testing/stage07`, `docs/android/07_*`, evidence и README.

## Ограничения и следующий этап

Нужны физические устройства для длительной проверки энергопотребления, OEM background limits, accessibility и взаимодействия двух реальных телефонов. Несколько устройств здесь проверялись несколькими настоящими клиентскими сессиями тестового сервера; это не аппаратный прогон на нескольких телефонах. Heap/leak-profiler не запускался; проверены очистка состояния и закрытие сокетов.

Поиск относится к спискам чатов, пользователей и папок; полнотекстового поиска внутри переписки нет. Сквозного шифрования сообщений backend не предоставляет. Direct signed URL может работать до своего срока (до пяти минут); уже экспортированные файлы отозвать невозможно. Роли/передача владения доступны в data API, UI управления ими пока отсутствует.

Перед отдельной задачей включения фоновых push нужны настоящий Firebase Android config и настроенный data-only HTTP gateway. Перед следующим продуктовым этапом повторно сверить backend commit/API. Записи, store-подпись/AAB и публикация приложения в этой задаче не выполнялись. Следующий этап автоматически не запускался.


## Визуальные свидетельства

[Планшет с группой, файлами и смешанной папкой](evidence/07/stage07-live-group-folder.png), [центр уведомлений](evidence/07/stage07-live-notifications.png), [системное сохранение файла](evidence/07/stage07-live-saf-create-document.png), [телефон, поворот и шрифт 150%](evidence/07/stage07-live-phone-font150.png).


## Очистка и артефакты

Временный backend/БД/Redis/MinIO остановлены и удалены; перед остановкой было 0 пользовательских WSS. Private CA, credentials и тестовые файлы удалены с хоста и эмулятора. 11 ранее работавших контейнеров сохранены. Дисплей 2560×1600 / 320dpi, fontScale1.0, IME setting0 и обе сети восстановлены. [Проверка очистки](evidence/07/cleanup.json).

Установочный debug APK: `app/build/outputs/apk/debug/app-debug.apk`; unsigned release: `app/build/outputs/apk/release/app-release-unsigned.apk`. SHA-256 и размеры сохранены в [07_VERIFICATION.json](07_VERIFICATION.json). Этап завершён.
