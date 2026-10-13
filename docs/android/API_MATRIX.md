# MeetSpace Android — матрица API и контракт адаптера

Baseline: `c861ec882633071da74c6825a3dbf2a19c1e32ed`, 11.10.2026. Это извлечение контрактов из кода, не утверждение о прохождении API tests. Production сообщает другой SHA; см. [аудит](00_AUDIT.md).

В таблицах prefix — `/api/v1`, `{id}` — UUID соответствующего ресурса. B = Bearer access token; C = `meetrix_session` cookie; G = guest token с conference scope; public = без credentials. Права доступа внутри B проверяются сервером; перечисление маршрута не означает доступ любого пользователя.

## 1. Общие правила сериализации

- REST преимущественно camelCase, UUID строками, timestamps RFC3339 → `Instant`; показывать время с выбранным timezone. `null`, отсутствующее поле и пустой список различать согласно DTO.
- Не вводить универсальный `data` wrapper: auth возвращает `user/accessToken`, CRUD часто `status/item`, списки `items`, а conversation/folder pages могут быть без `status`. [Response helper][errors], [personal handler][personal-handler], [folder handler][folder-handler].
- REST input строгий: unknown fields отклоняются; общий BindJSON ограничивает body 32 KiB. File upload идёт отдельным маршрутом. Ответы декодировать с допуском новых полей, unknown enums сохранять в `Unknown(raw)`.
- Типичная ошибка `{status:"failed",message:"..."}`; отдельные WS/SSE handlers могут вернуть `{error:...}` или пустое тело. Маппинг: 400 syntax, 401 auth, 403 forbidden, 404 missing, 409 conflict, 410 retired, 422 validation, 429 rate limit, 503 unavailable, 504 timeout. Не принимать HTML proxy error за успешный JSON. [Fail/BindJSON][errors].
- `sequence` сообщения сериализуется как **JSON string**, `version` — number; в Android обе величины без потери точности (`Long` после checked parsing). [Message DTO][chat-model].
- Pagination неодинакова: `limit/offset` у старых списков; `cursor` у timeline/notifications; `before` у conversations/messages/folder items. `nextCursor` передавать без разбора и не переиспользовать после смены фильтра/аккаунта.
- Idempotency существует только у отдельных операций: `clientRequestId` у message/init/group. Не добавлять произвольный `Idempotency-Key` в ожидании поддержки и не повторять создание встречи/записи после таймаута без reconciliation.

## 2. Server и auth

| Запрос | Auth | Контракт | Статус / решение Android | Источник |
|---|---|---|---|---|
| `GET /version` (без prefix) | public | `{version,commit,buildTime}` | implemented; проверять JSON/идентичность сервиса. Это не capabilities и не health | [Info][version] |
| `GET /capabilities` | B/G | `{status,capabilities,buildVersion}`; liveCaptions/transcription/aiSummary/semanticSearch/meetingAnalytics/recordingModes | partial discovery: нет полного Android protocol version или feature flags chats/folders | [handler][cap-handler], [DTO][capabilities] |
| `POST /auth/register` | public | `{email,password,displayName?}` → 201 `{status,user}` | implemented; регистрация не равна входу | [routes][auth], [handler][auth-handler] |
| `POST /auth/login` | public | `{email,password}` → `{status,accessToken,tokenType,expiresIn,user}` + Set-Cookie | implemented; `expiresIn=3600` в текущем service, sessionToken в JSON отсутствует | [DTO][user], [cookie][cookie] |
| `POST /auth/refresh` | C | `{}` → тот же LoginResponse + Set-Cookie | implemented; сохранить cookie при login, один refresh для параллельных 401 | [sessions][cookie], [service][refresh] |
| `POST /auth/session` | B (+ C при наличии) | `{}` → bootstrap legacy auth к persistent session | implemented; migration endpoint, не обычный refresh | [sessions][cookie] |
| `GET /auth/me` | B/G | `{status,user}` | implemented; проверка пользователя/guest scope | [routes][auth], [scope][scope] |
| `PATCH /auth/me` | B с разрешённым scope | `{displayName}` → `{status,user}` | partial profile: изменение email/пароля/avatar этим DTO не поддерживается | [request][profile] |
| `POST /auth/logout` | C и/или B | `{}` → `{status:"success"}`, очистка cookie и revoke сессии | implemented; offline revoke не выдавать за подтверждённый | [handler][auth-handler], [sessions][refresh] |

Native запросы без Origin/Fetch Metadata допускаются [sameOrigin][cookie]. Cookie host-only, Path `/api/v1/auth`, HttpOnly, Secure в non-local, SameSite=Lax. Пароль не хранить. REST Bearer не заменяет C для refresh; невозможность прочитать HttpOnly из browser JS не мешает native CookieJar.

## 3. Встречи и участники

| Запрос | Auth / данные | Результат / Android | Статус / источник |
|---|---|---|---|
| `POST /conferences` | B; `{title,waitingRoomEnabled,scheduledAt?,plannedDurationMin?}` | Conference с inviteCode/inviteUrl/status | implemented; [routes][conferences], [DTO][conference-model] |
| `GET /conferences`, `GET /conferences/{id}` | B; список `limit/offset`; G ограничен своей встречей | список/одна Conference | implemented; [routes][conferences] |
| `GET /me/conferences` | B; `view`, `scope`, `status`, `cursor`, `limit`, `from/to` в RFC3339 с timezone | `{items,nextCursor}`; главная/календарь/история | implemented; [timeline][timeline] |
| `PUT /conferences/{id}/schedule` | B; `{scheduledAt,plannedDurationMin}` | новое расписание | implemented; [routes][conferences] |
| `POST /conferences/{id}/{start,finish,cancel}` | B + серверная роль | переход статуса, не локальное переключение | implemented; [routes][conferences], [states][conference-state] |
| `GET /conference-invites/{code}` | public | публичное описание приглашения | implemented; не даёт доступ к private meeting data; [routes][conferences] |
| `POST /conference-invites/{code}/join` | B | join по коду | implemented; [routes][conferences] |
| `POST /conference-invites/{code}/guest` | public; `{displayName}` | guest credentials/meeting result согласно handler | implemented; срок и scope не смешивать с account session; [route][guest] |
| `POST /conferences/{id}/{join,leave}` | B/G, membership | join/leave; pending admission обрабатывается отдельно | implemented; [routes][conferences] |
| `GET /conferences/{id}/participants`, `/participants/me` | B/G | participant id, role, admission, media policy | implemented; [routes][conferences] |
| `POST /conferences/{id}/participants/{participantId}/admission` | B + роль; `{decision}` | решение допуска | implemented; [routes][conferences] |
| `PUT /conferences/{id}/participants/me/media` | B/G; `{connectionId,sequence,microphoneEnabled,cameraEnabled,screenSharing}` | media presence текущего физического соединения | implemented; [DTO][moderation], [route][control] |
| `POST /conferences/{id}/participants/{participantId}/moderation` | B + роль; `{action,blocked?,role?}` | серверная policy/роль | implemented; [DTO][moderation] |
| `GET /conferences/{id}/history` | B с доступом | conference/owner/duration/participants/recording summary/chat access | implemented; не журнал телефонных звонков; [routes][conferences] |
| `GET /conferences/{id}/invitation-users`, `POST /conferences/{id}/invitations` | B; поиск `query`, создание `{emails?,userIds?}` | lookup / 202 результаты приглашений | implemented; 202 не подтверждает доставку email; [routes][invitations] |

Статусы conference: created, scheduled, active, finished, cancelled. Для вызова SFU нужны membership/admission и WS, а не просто successful login. Id конференции, participantId, userId, connectionId, sessionId и mediaPeerId — разные сущности; не заменять их одним `callId` в DTO. [Models][conference-state], [Envelope][envelope], [Media][media-model].

## 4. Conversations, messages, folders

| Запрос | Контракт/ограничение | Статус / источник |
|---|---|---|
| `GET /users?search=...` | B account only; найденные пользователи | implemented; [routes][personal] |
| `GET /conversations` | B; `before,limit,type=direct\|group,unreadOnly,search`; `{items,nextCursor?,unreadCount}`. Без type — все | implemented; [handler][personal-handler], [model][personal-model] |
| `POST /conversations/direct` | B; `{userId}` → `{status,item}`; существующий direct может быть переиспользован | implemented; [routes][personal] |
| `POST /conversations/group` | B; `{clientRequestId,name,description,memberIds}`; name≤50, description≤200, всего≤100 members | implemented; [validation][groups] |
| `GET/PATCH/DELETE /conversations/{id}` | GET для direct/group; PATCH metadata и DELETE — group semantics, права сервера | implemented; [routes][personal], [group handler][group-handler] |
| `GET/POST /conversations/{id}/members` | список / `{userIds:[...]}` | implemented; [handler][group-handler] |
| `DELETE/PATCH /conversations/{id}/members/{userId}` | удалить / `{role}` | implemented; owner/admin/member; [routes][personal], [roles][groups] |
| `POST /conversations/{id}/{leave,ownership}` | leave / ownership `{userId}` | implemented; [handler][group-handler] |
| `PATCH /conversations/{id}/preferences`, `POST .../clear-history`, `POST .../hide` | личные настройки direct и действия текущего пользователя; не удаление переписки у собеседника | implemented; [routes][personal], [event guards][delivery] |
| `GET /conversations/{id}/peer-presence` | presence собеседника; не установка собственного online/DND статуса | implemented; [routes][personal] |
| `PUT/DELETE /conversations/{id}/avatar`, `GET .../avatar/content` | групповой avatar, разрешения/формат через server handler | implemented; не avatar пользователя; [assets][assets] |
| `GET /{scope}/{id}/messages` | scope=`conferences` или `conversations`; `before,limit` → `{status,items,nextCursor,unreadCount,lastReadMessageId}` | implemented; [routes][chat] |
| `POST /{scope}/{id}/messages` | `{clientRequestId,text,replyTo?,attachmentIds?}`; UUID, text≤4000 runes, ≤5 attachments | implemented; stable clientRequestId при retry; [DTO][chat-model], [handler][chat-handler] |
| `PATCH/DELETE /{scope}/{id}/messages/{messageId}` | edit `{text}` / delete; server authority и tombstone | implemented; [handler][chat-handler] |
| `GET/PUT /{scope}/{id}/chat/read` | read state / mark read `{messageId}`; для conversation также `POST .../{id}/read` | implemented; [routes][chat], [personal alias][personal] |
| `POST /{scope}/{id}/attachments/init` | `{clientRequestId,filename,mimeType,size}` → item + relative uploadUrl | implemented; размер файла ≤10 MiB, pending TTL 30m; [DTO][chat-model], [handler][chat-handler] |
| `PUT .../attachments/{attachmentId}/content`, `POST .../finalize` | binary upload → finalize с `{}`; не multipart, не обход API прямым S3 write | implemented; [routes][chat] |
| `GET .../attachments/{attachmentId}/download` | `{status,url,expiresAt}`, короткоживущий URL | implemented; separate download client без credentials; [handler][chat-handler] |
| `GET /conversations/{id}/attachments/{attachmentId}/content` | приватная выдача через API с проверкой membership | implemented; [assets][assets] |
| `/conferences/{id}/chat/{info,members,preferences,membership,messages/search,messages/{messageId}/context,materials,pins}` | Методы и поля различаются; смотреть маршруты. Pins используют PUT/DELETE `.../pins/{messageId}` | implemented только conference actions; [routes][chat-actions] |
| `GET/POST /folders`, `GET/PATCH/DELETE /folders/{id}`, `PUT /folders/order` | B account only; персональные папки, name≤50, ≤100 folders | implemented; [routes][folders], [model][folder-model] |
| `GET /folders/{id}/items`, `GET /folder-items` | `before,limit,type,search`; candidates дополнительно `folderId` | implemented; [handler][folder-handler] |
| `PUT/DELETE /folders/{id}/items/{kind}/{itemId}` | kind только `conversation`/`conference`; папка не расширяет права на item | implemented; [routes][folders], [validation][folder-model] |

Поля `Conversation`: `type`, nullable `peer`, `name`, `description`, `memberCount`, `myRole`, `avatarVersion`, `lastSender`, `lastMessageId`, `preview`, `unreadCount`, `notificationsEnabled`, `historyClearedThrough`. В UI не угадывать group по имени/id и не подставлять фиктивные online/memberCount. [DTO][personal-model].

## 5. Записи, уведомления, optional features

| Запрос | Auth / результат | Статус / источник |
|---|---|---|
| `POST /conferences/{id}/recordings` | B + права; `{mode?,segmentDurationSec?}` → 202 `{status,item}` | implemented; [routes][records], [service][record-service] |
| `GET /conferences/{id}/recordings`, `GET .../{recordingId}` | B с доступом; list limit/offset; RecordCard/files с доступными ссылками | implemented; [routes][records] |
| `POST .../recordings/{recordingId}/stop` | B + права → 202; дальше processing/ready/failed | implemented; [service][record-service] |
| `/api/v1/records`, `/api/records`, `/debug/records` | 410 Gone, все методы/подпути | retired; не использовать legacy SDP ingest в Android; [retirement][retired] |
| `GET /notifications?cursor=...&limit=...`, `POST /notifications/{id}/read` | B; items,nextCursor,unreadCount / прочтение | implemented; [routes][notifications] |
| `GET /notifications/events` | B; SSE без query, JSON envelope в `data:`; `notification.created`/`notification.read` | implemented; refetch после reconnect, durable replay не обещан; [handler][sse] |
| `GET/PUT /notifications/preferences` | B; категории и каналы | implemented; server preferences и Android notification permission независимы; [routes][integrations] |
| `GET/POST /notifications/devices`, `DELETE .../{deviceId}` | B; register `{platform:"android",token}`; noop → unavailable | partial mobile integration; [service][device] |
| `GET /integrations/capabilities`, `/integrations/calendars` | B; configured provider modes и connections | implemented contract, runtime unknown; [routes][integrations] |
| Calendar connect/callback/disconnect, conference calendar mappings | B; OAuth/provider-specific integration | partial mobile redirect/PKCE/deep-link contract не проверен; [routes][integrations] |
| `/conferences/{id}/recordings/{recordingId}/transcript`, `/transcript/segments`, `/summary`; POST retry/regenerate | B, capabilities + server permissions | partial: providers optional; [content routes][content] |
| `GET /search`; captions GET/PUT/segments, conference analytics | B, capabilities | partial: feature gating; [content][content], [captions][captions] |

Режимы записи: composite, audio_only, individual_tracks, screen_focus — проверять строковые значения [в константах/валидации][record-model] и разрешённый список capabilities. Сам UI кнопкой «Остановлено» не завершает FFmpeg/upload. Presigned URL может истечь; перечитать RecordCard и возобновить playback, сохранив позицию. Offline download — отдельное продуктовое решение о retention и приватности.

## 6. Два WebSocket-контракта

| Канал | Подключение | Клиентские сообщения | Восстановление |
|---|---|---|---|
| Global user | `POST /ws-ticket` с B → `{ticket,expiresAt}`; `GET /ws?ticket=...` | **Нет application commands**. GET с Authorization отвергается; ping/pong транспортный | Новый одноразовый ticket, подписка + REST refetch. Personal message/conversation/presence события; [handler][user-ws], [stream][user-stream] |
| Conference | `GET /conferences/{id}/ws` с B **либо** `POST .../ws-ticket` с B → GET с ticket | Envelope v1, главным образом `media.*`; [legacy webrtc relay][ws] не использовать для SFU | Новый WS identity, state snapshot, новый media peer после cleanup; [connect][ws-auth] |

SSE закрывается по сроку JWT даже для durable account session; после refresh открыть новый поток и перечитать REST notifications. Keepalive — SSE comment каждые 15 секунд, это отдельный механизм от WS ping/pong. [SSE handler][sse].

Subprotocol `go-recorder.v1`. Conference native может подключаться с Bearer без браузерного Origin; выбирать ровно один механизм авторизации. Для global требование ticket-only обязательно. Никогда не писать URL с ticket в log; bearer JWT в query не добавлять.

Envelope conference:

```json
{
  "version": 1,
  "id": "<new UUID>",
  "type": "media.join",
  "conferenceId": "<conference UUID>",
  "timestamp": "<RFC3339 UTC>",
  "data": {}
}
```

Это схема с placeholders, не готовый запрос. Client `replyTo` запрещён, server связывает response по `replyTo`. Global payload использует этот же envelope, но conferenceId может быть пустым, а conversationId находится внутри data. [Envelope][envelope], [global delivery][delivery].

Нужны реальные WebSocket pong frames в ответ на ping, а не JSON heartbeat. [Defaults][ws-config] задают короткий бюджет присутствия (1s ping + 4s timeout, TTL 5s); мобильная сеть может вызывать частые reconnect. Постоянная account session после upgrade проверяется на отзыв и не обязана рвать звонок через час JWT; guest/legacy expiration остаётся отдельным режимом. [Durable WS][ws-durable]. Проверять этот сценарий более чем часовым звонком, не переносить старую документацию об expiry без проверки кода.

## 7. SDP / ICE / SFU

| Событие | Направление | Данные и обязательный порядок |
|---|---|---|
| `conference.state` | S→C | connectionId, participantId, status, participants; baseline membership/presence |
| `media.join` → `media.joined` | C→S→C | `{}` → mediaPeerId, workerId, maxPeers, iceServers, tracks, videoCapture, policy |
| `media.offer` → `media.answer` | C→S→C | `{mediaPeerId,negotiationId,sdp,publications:[{mid,source,trackId?}]}` → matching IDs + SDP |
| `media.ready` | C→S | `{mediaPeerId,negotiationId}` **после успешного setRemoteDescription(answer)**; ждать корреляцию ack/error |
| `media.ice` | C↔S | mediaPeerId и RTCIceCandidateInit-shaped candidate либо null; буферизовать до remote SDP |
| `media.renegotiate` | S→C | revision; coalesce revisions, offer создаёт только клиент |
| `media.tracks` | S→C | revision + полный каталог tracks; отклонять устаревшие snapshots |
| `media.published/unpublished`, `media.state`, `media.policy` | S→C | publication lifecycle, peer state, server moderation policy |
| `media.unpublish`, `media.leave` → `media.left` | C→S→C | собственный trackId/peerId; пустой leave отменяет pending join |

Источники: [Signal DTO][media-model], [controller dispatch][media-controller], [reference negotiation][media-ready], [frontend transceivers][media-client]. Source строки: `microphone`, `camera`, `video/screen`, `audio/screen`. Server streamId для screen имеет suffix `-screen`. Использовать реальные MID после setLocalDescription; не сериализовать client identity/binding в media command.

SDP ≤49 152 bytes, ICE ≤4096 bytes; Opus 48kHz stereo + VP8 90kHz. [DTO][media-model], [codecs][sfu-codecs]. H264/simulcast/SVC не предполагаются. SDP должен сохранять BUNDLE и Unified Plan; не делать глобальный string replacement кодеков без необходимости/проверок.

`GET /webrtc/config` с B/G → `{iceServers,iceTransportPolicy?,expiresAt?}`; expiry — Unix seconds. TURN credentials короткоживущие, default TTL 10m. [Generator][turn]. В worker Result поля называются `iceTransportPolicy`/`iceExpiresAt`, но **в текущем `media.joined` оба теряются**: [worker][media-ice] → [controller][media-joined]. Блокер R02: согласовать wire DTO, добавить contract test и доказать выбранный candidate pair `relay`. TLS handshake сам по себе этого не доказывает.

## 8. Контракты, которых не хватает

| Пробел | Решение до реализации соответствующей функции |
|---|---|
| Production source/manifest | Установить воспроизводимую baseline и staging с конкретным SHA |
| OpenAPI/AsyncAPI/golden JSON | Зафиксировать реальные response fixtures и JSON schemas; этот документ не заменяет автоматическую проверку |
| Полный capabilities/protocol version | Добавить отдельным backend change; пока explicit supported baseline и graceful unavailable |
| Ring/accept/decline/missed и background wake-up | Не имитировать звонок на основе одной только conference creation; отдельный протокол |
| FCM/provider delivery и devices без Google Play services | Выбрать поддерживаемый канал, проверить token rotation/revoke, Doze и permission denied |
| Общая библиотека записей | MVP выбирает встречу, как web UI; глобальный агрегирующий endpoint — отдельное расширение |
| Profile email/password/avatar/DND | Показать доступные действия; не отправлять unsupported поля в PATCH /auth/me |
| Resume после пропущенных events | REST reconciliation; durable cursor/replay отсутствует в проверенном WS API |
| SSH installer jobs | Новый контракт по [модели угроз](INSTALLER_THREAT_MODEL.md); не публичный endpoint существующего meeting API |

Перед релизом required integration cases: login→refresh→restart→logout/revoke, account/guest scope isolation, группа/удаление участника, same clientRequestId retry, expiry вложения, reconnect/refetch, 403/409/429, waiting room/finish, SDP answer/ready ordering, forced TURN relay, истечение credentials, длительный call и настоящая серверная запись. Сейчас эти cases не запускались.

[assets]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/personal_asset_routes.go#L11
[auth]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/platform_routes.go#L19
[auth-handler]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/auth/handler.go#L98
[cap-handler]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/platform/handler.go#L24
[capabilities]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/platform/platform.go#L7
[captions]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/captions_routes.go#L12
[chat]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/chat_routes.go#L23
[chat-actions]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/chat_actions_routes.go#L12
[chat-handler]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/chat/handler.go#L83
[chat-model]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/chat/chat.go#L20
[conference-model]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/conferences/conference.go#L259
[conference-state]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/conferences/conference.go#L18
[conferences]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/platform_routes.go#L33
[content]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/content_routes.go#L12
[control]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/platform_routes.go#L63
[cookie]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/auth/sessions.go#L18
[delivery]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/websocket/user_delivery.go#L49
[device]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/integrations/service.go#L226
[envelope]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/realtime/realtime.go#L39
[errors]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/httpresponse/response.go#L22
[folder-handler]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/folders/handler.go#L182
[folder-model]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/folders/folders.go#L66
[folders]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/folder_routes.go#L10
[group-handler]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/personal/group_handler.go#L14
[groups]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/personal/groups.go#L32
[guest]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/guest_routes.go#L10
[integrations]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/integration_routes.go#L13
[invitations]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/invitation_routes.go#L11
[media-client]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/frontend/src/media.ts#L1570
[media-controller]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/media/controller.go#L278
[media-ice]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/mediaworker/handler.go#L689
[media-joined]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/media/controller.go#L402
[media-model]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/media/media.go#L230
[media-ready]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/frontend/src/media.ts#L1428
[moderation]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/conferences/moderation.go#L12
[notifications]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/notification_routes.go#L14
[personal]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/personal_routes.go#L11
[personal-handler]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/personal/handler.go#L66
[personal-model]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/personal/personal.go#L10
[profile]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/users/user.go#L95
[record-model]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/records/conference.go#L15
[record-service]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/recordings/service.go#L45
[records]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/conference_recording_routes.go#L14
[refresh]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/auth/sessions.go#L118
[retired]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/retired_recording_routes.go#L17
[scope]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/http/middleware/auth.go#L26
[sfu-codecs]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/infrastructure/sfu/manager.go#L223
[sse]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/notifications/handler.go#L171
[timeline]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/conferences/product_handler.go#L101
[turn]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/config/turn.go#L66
[user]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/users/user.go#L130
[user-stream]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/websocket/user_handler.go#L229
[user-ws]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/websocket/user_handler.go#L118
[version]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/buildinfo/buildinfo.go#L15
[ws]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/websocket/handler.go#L166
[ws-auth]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/websocket/handler.go#L325
[ws-config]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/config/realtime.go#L19
[ws-durable]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/websocket/handler.go#L398
