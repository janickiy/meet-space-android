# Этап 07 — план до реализации

Запрос: личные и групповые чаты, папки, файлы, уведомления. Вложение `CODEX_ANDROID_07_07_MESSAGING_FOLDERS.md` используется как требования этого этапа. Android 0.6.0 — исходное состояние; следующий этап автоматически не начинается.

## Проверенный исходный контракт

11.10.2026 публичный HEAD backend и чистый локальный `.audit/meet-space-stage04` совпадают: `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. Работающий checkout и production не изменяются. Дизайн — первая версия `design/meetspace-android-20261010`, экраны messages-tablet/messages-phone-dark.

- `/users`, `/conversations` поддерживают поиск пользователей, direct/group, membership и роли, сообщения/reply/edit/delete, read/unread. `clientRequestId` UUID служит ключом идемпотентности. Разные endpoints используют `before` или `cursor`; точный контракт сохраняется отдельно.
- Attachments: init → authenticated binary PUT → finalize → включение attachment IDs в сообщение; до 10 MiB/файл и 5 вложений. Group download может вернуть authenticated API URL, direct — временный S3 URL. Bearer нельзя передавать стороннему storage; HTTPS/hostname проверки обязательны.
- Global user WebSocket — отдельный `/ws-ticket` → `/ws`, server events без команд клиента и без conference subprotocol. После reconnect нужна REST сверка; события не являются гарантированным журналом.
- Персональные папки содержат conversation/conference и не расширяют права на них. Notification inbox/read/preferences и календарные события реализованы.
- API регистрации Android push devices существует, но provider — общий `noop/mock/http` gateway. Firebase project и доставка FCM не подтверждены; у пользователя запрошена конфигурация. Нельзя выдавать device registration или mock provider за доставленный push.

## План реализации

1. Типизированные модели и MessagingRepository поверх существующего origin/account auth context. REST, bounded WSS, безопасные URL файлов, cursor pagination и server-side permissions.
2. ViewModel с поколением auth scope, дедупликацией сообщений и cursor guards, стабильным request ID при ручном повторе неизвестного результата. Ограниченная история в памяти текущей сессии доступна при кратком offline; logout/switch/process restart очищают её. Автоматическая offline отправка не выполняется.
3. Адаптивный двухпанельный чат планшета, одна панель телефона, поиск/фильтры/создание direct/group, reply/edit/delete, участники группы, read/unread. Папки: создание/переименование/удаление, чаты и встречи. Центр уведомлений, настройки и переходы к доступным встречам/чатам.
4. Android file picker и экспорт через Storage Access Framework: ограниченный размер, отсутствие storage permission, очищаемые временные данные, загрузка только после проверки доступа. Системные уведомления — с runtime consent, без секретов в payload. FCM только с подтверждённой конфигурацией проекта и gateway; без неё честное ограничение фоновой доставки.
5. Реальный disposable backend + PostgreSQL/Redis/private MinIO. Проверки нескольких пользователей/устройств, REST/WSS reconnect, idempotency, IDOR, attachments, folders, notifications. JVM/Compose/Android integration, APK, отчёт и cleanup.

## Известные ограничения до начала

- Смена аккаунта/сервера должна отменить все запросы, WebSocket, историю, drafts, file bytes и видимые notification previews.
- Внешний push/Doze и доставка при закрытом приложении без Firebase/gateway не подтверждаются локальным стендом.
- Поиск сообщений не обещается для endpoint, который backend не предоставляет. UI должен ясно отличать поиск чатов/пользователей от поиска текста в истории.
- Результаты тестов и runtime поведение ещё не подтверждены этим планом; будут зафиксированы в итоговом отчёте.
