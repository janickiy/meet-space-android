# Этап 07 — фактический контракт backend

Проверен checkout `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. Источник: реальные `internal/transport/http/{personal,chat,folder,notification,integration}_routes.go`, `internal/transport/websocket/user_handler.go`, `internal/domain/{chat,personal,folders,notifications}` и реализующие их PostgreSQL/usecase файлы. Старый `docs/GROUP_CHATS.md` говорит, что папок нет; текущие routes и миграция 32 уже содержат папки.

Префикс REST — `/api/v1`; пользователь определяется Bearer-сессией. Каждый ID и cursor передаётся без изменения. Доступ определяется текущим членством на сервере, а наличие папки или ранее загруженной истории прав не добавляет.

## Чаты и сообщения

| Операция | Метод и путь | Существенный контракт |
|---|---|---|
| Поиск пользователя | `GET /users?search=…` | `items: [{id,displayName}]`; только зарегистрированные аккаунты |
| Список диалогов | `GET /conversations` | `type=direct|group`, `search`, `unreadOnly`, `before`, `limit`; raw page `{items,nextCursor,unreadCount}` |
| Личный диалог | `POST /conversations/direct` | `{userId}`; пара уникальна, 201 при создании, 200 при повторе |
| Группа | `POST /conversations/group` | `{clientRequestId,name,description,memberIds}`; повтор одинакового запроса возвращает прежний ID |
| Детали | `GET /conversations/:id` | `{status,item}` с типом, собеседником/названием и ролью текущего пользователя |
| Метаданные группы | `PATCH /conversations/:id` | `{name?,description?}`; owner/admin |
| Участники | `GET/POST /conversations/:id/members` | GET: `{items:[{id,displayName,role,online}]}`; POST: `{userIds}` |
| Роль / исключение | `PATCH/DELETE /conversations/:id/members/:userId` | PATCH: `{role:"admin"|"member"}`; полномочия проверяет сервер |
| Владение / выход / удаление | `POST /conversations/:id/ownership`, `POST /conversations/:id/leave`, `DELETE /conversations/:id` | Передача `{userId}`; owner с другими участниками сначала передаёт владение |
| История | `GET /conversations/:id/messages?before=…&limit=…` | `{items,nextCursor,unreadCount,lastReadMessageId}`; сообщения содержат `sequence` строкой, `version`, `clientRequestId` |
| Отправка / ответ | `POST /conversations/:id/messages` | `{clientRequestId,text,replyTo?,attachmentIds?}`; 201/200; тот же ключ с другим телом — 409 |
| Правка / удаление | `PATCH/DELETE /conversations/:id/messages/:messageId` | PATCH `{text}`; только собственное сообщение; удаление сохраняет tombstone, очищая текст |
| Прочтение | `PUT /conversations/:id/chat/read` | `{messageId}`; `{item:{unreadCount,lastReadMessageId}}`; граница монотонна |
| Presence | `GET /conversations/:id/peer-presence` | Только direct; `{item:{conversationId,peerId,online}}`; несколько устройств одного аккаунта учитываются вместе |
| Личные уведомления диалога | `PATCH /conversations/:id/preferences` | `{notificationsEnabled}`; direct-only |

Ограничения backend: текст до 4000 Unicode символов; максимум 5 вложений по 10 MiB; группа до 100 активных участников, имя до 50 символов, описание до 200. Удалённый из группы аккаунт теряет историю, файлы и последующие приватные WS-события. Авторство правки/удаления сообщения не расширяется ролью администратора группы.

## Файлы

1. `POST /conversations/:id/attachments/init` с `{clientRequestId,filename,mimeType,size}` возвращает `{item,uploadUrl}`. ID загрузки стабилен при корректном повторе.
2. `PUT uploadUrl` передаёт raw bytes с авторизацией API. Backend проверяет размер, фактический MIME, UTF-8/изображения и пишет приватный объект MinIO.
3. `POST /conversations/:id/attachments/:attachmentId/finalize` подтверждает объект (`status=ready`, размер, SHA-256).
4. Отправка сообщения с `attachmentIds` атомарно привязывает объект (`status=attached`). Вложение чужого диалога не принимается.
5. `GET /conversations/:id/attachments/:attachmentId/download` имеет **два реальных варианта**:
   - group: относительный API URL `.../content`, `authenticated:true`; каждый запрос байтов заново проверяет членство;
   - direct: подписанный S3 URL, срок до пяти минут; запрос к хранилищу выполняется без JWT/cookies. HTTPS обязателен для Android.

Приватный bucket отклоняет unsigned GET. Подписанная direct ссылка является временным правом на получение объекта; уже выданная ссылка может действовать до истечения срока. Это ограничение серверного контракта, а не обещание немедленного отзыва скачанных байтов. В HTML/JSON отчёты и журналы signed URL не попадают.

## Глобальный пользовательский WebSocket

`POST /ws-ticket` с Bearer возвращает 201 `{ticket,expiresAt}`; ticket — одноразовый. `GET /ws?ticket=…` открывает WSS **без Bearer и без обязательного subprotocol**. В отличие от conference WebSocket, `go-recorder.v1` здесь не требуется. Другие query-параметры и Bearer на upgrade отклоняются. Это server-only поток; изменения выполняются через REST. Ping/pong обслуживает WebSocket транспорт.

События в envelope v1: `message.created|updated|deleted`, `conversation.updated`, `conversation.member.added|updated|removed`, `conversation.read.updated`, `conversation.preferences.updated|history.cleared|hidden`, `folder.created|updated|deleted|items.updated|reordered`, `notification.created|read`, `user.presence`. У глобальных событий нет conference scope в envelope; необходимые идентификаторы находятся в `data`. Message event содержит `{conversationId,type,message}`; folder event — только `{folderId}` либо пустую нагрузку для reorder. Folder events приходят только устройствам владельца папки.

Начального полного snapshot, replay cursor или гарантированного восстановления пропущенных событий нет. После reconnect клиент заново читает REST списки, выбранную историю, папки, счётчики и уведомления. Pub/Sub используется как сигнал обновления; PostgreSQL остаётся источником состояния. Для исключённого участника сервер повторно проверяет права перед доставкой payload.

## Папки и уведомления

| Операция | Контракт |
|---|---|
| `GET/POST /folders` | Список `{items}`; создание `{name}` → `{item}` |
| `GET/PATCH/DELETE /folders/:id` | Получение, переименование `{name}`, удаление только личной папки |
| `PUT /folders/order` | `{ids}` — полный порядок папок текущего пользователя |
| `GET /folders/:id/items` | Mixed page: `{items:[{type:"conversation"|"conference",item}],nextCursor}`; `type/search/before/limit` |
| `GET /folder-items` | Кандидаты; `folderId`, `type`, `search`, `before`, `limit`; элемент может содержать `inFolder` |
| `PUT/DELETE /folders/:id/items/:kind/:itemId` | `kind=conversation|conference`; папка не даёт новых прав на ресурс |
| `GET /notifications?cursor=…&limit=…` | Raw `{items,nextCursor,unreadCount}`; календарные приглашения содержат ссылочную payload, не сообщения/секреты |
| `POST /notifications/:id/read` | Идемпотентное прочтение собственного уведомления |
| `GET/PUT /notifications/preferences` | Raw preferences: invitation/reminder/recording/summary/email/push |
| `GET /integrations/capabilities` | Raw capabilities, в том числе `push` |
| `POST /notifications/devices` | `{platform:"android",token}`; backend умеет регистрацию, но `push=noop` возвращает 503 |
| `DELETE /notifications/devices/:id` | Отзыв собственной регистрации; 204 |

FCM SDK/project configuration и рабочий push gateway не следуют из наличия REST регистрации. Backend использует общий provider gateway; реальная доставка на устройство требует отдельной подтверждённой конфигурации. Fixture честно использует `noop`, не имитирует доставку. Встроенный центр уведомлений и обновления активного приложения работают через REST/WSS.

## Проверка

`testing/stage07` создаёт изолированные PostgreSQL 14, Redis 7, MinIO и реальный Gin backend с проверяемым HTTPS/WSS. [Протокол backend](evidence/07/backend-contract.json) содержит 458 прошедших assertions (HTTP-коды, фактические состояния, байты файлов, WS-доставка). [JVM-интеграция](evidence/07/live-repository.xml) — три native repository теста, без skipped/failures. Они проверяют реальные idempotency/read/edit/delete/reconnect, права, MinIO bytes/checksum/TLS, группы/папки, настройки и noop push.

После закрытия всех probe-сокетов `snapshot.json` показал `userWebSocketConnections=0`. Это проверка освобождения серверных соединений, а не профилирование heap Android. Долговременный offline cache и системная push-доставка этим backend-протоколом не проверяются. Production и чужие контейнеры не изменялись.
