# Этап 05 — проверенный REST-контракт

Проверено 11.10.2026 по backend `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`, чистый checkout `.audit/meet-space-stage04`. Источники: `internal/transport/http/platform_routes.go`, `internal/app/conferences/{handler,product_handler}.go`, `internal/domain/conferences/{conference,product}.go`, `internal/usecase/conferences/{service,product}.go`, PostgreSQL repositories и `internal/app/auth/handler.go`. Production при этом аудите не изменялся.

## Маршруты Android

Все маршруты ниже требуют Bearer access token текущего аккаунта, кроме публичного чтения приглашения. Успех содержит `status: "success"`; элементы называются `item`, списки — `items`.

| Экран / действие | Метод и путь | Тело / ответ | Права и ограничения |
|---|---|---|---|
| Главная, список, календарь | `GET /api/v1/me/conferences` | `items: Conference[]`, `nextCursor: string|null` | Только свои членства; фильтры ниже |
| Детали | `GET /api/v1/conferences/{id}` | `item: Conference` | Существующее членство; приглашение скрыто при недостаточном допуске |
| Создание | `POST /api/v1/conferences` | `{title,waitingRoomEnabled,scheduledAt?,plannedDurationMin?}` → 201 `item` | Текущий пользователь становится owner, admitted/left |
| Изменение расписания | `PUT /api/v1/conferences/{id}/schedule` | `{scheduledAt,plannedDurationMin?}` → `item` | Только owner, только `scheduled` |
| Начать / завершить / отменить | `POST /api/v1/conferences/{id}/{start|finish|cancel}` | Без обязательного тела → `item` | Только owner, разрешённые переходы ниже |
| Свой допуск | `GET /api/v1/conferences/{id}/participants/me` | `item: Participant` | Отсутствие членства возвращает 403; 403 не означает «можно войти» |
| Войти в существующее членство | `POST /api/v1/conferences/{id}/join` | `{inviteCode?}` → `item: Participant` | Новый участник обязан предъявить действующий код; kicked/rejected не возвращаются |
| Присоединиться по приглашению | `POST /api/v1/conference-invites/{code}/join` | Без обязательного тела → `item: Participant` | Код — 32 символа strict RawURL Base64, декодируется в 24 байта |
| Выйти | `POST /api/v1/conferences/{id}/leave` | Без обязательного тела → `item: Participant` | Членство сохраняется; медиа выключаются |
| История | `GET /api/v1/conferences/{id}/history` | `item: History` | Только `finished|cancelled`, admitted и joined/left |
| Профиль | `PATCH /api/v1/auth/me` | `{displayName}` → `user` | Только своё имя, без смены email / пароля / аватара |

Публичный `GET /api/v1/conference-invites/{code}` возвращает ограниченное `item` с id, title, status, waitingRoomEnabled и scheduledAt. `inviteUrl` из подробного Conference — относительный `/api/v1/conference-invites/{code}`; origin берётся только из выбранного доверенного HTTPS-профиля. Код и URL не выводятся в логи и не сохраняются в Bundle.

## Поля

`Conference`: `id`, `ownerId`, `title`, `inviteCode`, `inviteUrl`, `status`, `createdAt`, `updatedAt`, nullable `startedAt`, `finishedAt`, `scheduledAt`, `plannedDurationMin`, `waitingRoomEnabled`. Timeline намеренно возвращает пустые `inviteCode` / `inviteUrl`. Поля количества участников в Conference нет: Android не выдумывает счётчики.

`Participant`: `id`, `conferenceId`, nullable `userId`, `displayName`, `role`, `status`, `admissionState`, nullable `joinedAt`, `leftAt`, а также серверные медиа / admission версии. Роли: `owner`, `co_host`, `participant`, `guest`. Статусы членства: `joined`, `left`, `waiting`, `rejected`, `kicked`. Admission: `admitted`, `waiting`, `rejected`, `kicked`; legacy пустой admission соответствует admitted только для joined/left. Media participation требует admitted + joined.

`History`: `conference`, `owner: {id,displayName}`, nullable `durationSec`, `participantCount`, `participants`, `participantsTruncated`, `recordings: {total,ready,processing,failed}`, `chatAvailable`, `chatReadOnly`. Массив ограничен первыми 100 участниками; число может быть больше массива. Owner/co_host видят все admission-состояния, остальные — admitted. Чаты и записи не реализуются в этом этапе.

## Timeline и календарь

- `view=upcoming|active|past`, по умолчанию upcoming. Upcoming — created/scheduled; active — active; past — finished/cancelled с admitted членством и joined/left.
- `scope=all|owned|participating`, по умолчанию all. Owned означает ownerId=currentUser; participating исключает владельца, а не обозначает joined.
- `limit` 1..100, по умолчанию 20; `cursor` непрозрачный, не более 256 символов. Сервер читает limit+1, возвращает nextCursor при продолжении. Android не интерпретирует cursor и останавливает повторённый cursor.
- Дополнительный backend `status` поддерживает created/scheduled/active/finished/cancelled, но отдельный фильтр статуса Android не добавляет.
- `from` / `to` — RFC3339Nano с явным часовым поясом; from ≤ to. Границы **включены**: ≥from, ≤to.
- Дата сортировки и фильтрации — `COALESCE(finished_at, scheduled_at, created_at)`, порядок DESC(date,id). Это не всегда время фактического старта. История в календаре попадает на дату завершения.
- Календарь объединяет все три view, каждую страницу отдельно, с границами локального месяца, переведёнными в Instant выбранного ZoneId. Верхняя граница — следующая локальная полночь минус 1 µs, согласованная с точностью PostgreSQL. Реальная проверка границы подтвердила включение последней µs марта 2030 в America/New_York и исключение точного начала апреля.
- Членства admitted/waiting доступны timeline; скрытые через `conference_chat_preferences.left_at` не возвращаются. Нет total/count и отдельного calendar endpoint. Недозагруженные страницы должны быть видимы пользователю как неполные данные.

## Жизненный цикл и время

`created|scheduled → active|cancelled`, `active → finished`. Иных переходов нет. Owner определяется по ownerId, а не произвольной роли; co_host не получает start/finish/cancel или schedule.

Title после trim — 1..200 Unicode code points. DisplayName — 1..100 code points, без Unicode control characters. Запланированное время обязано быть строго в будущем; duration 1..1440 минут, только вместе с scheduledAt. Время хранится UTC, локальные date/time требуют ZoneId. Несуществующее время DST отклоняется; повторяющееся время требует выбора правильного UTC offset.

Создание обычной встречи даёт created, с scheduledAt — scheduled. Owner сначала admitted/left. При входе по приглашению в scheduled сервер регистрирует admitted/left; сервер не утверждает, что человек уже подключил аудио/видео. Ссылка даёт немедленный admission, включая бывшее waiting; rejected/kicked остаются запрещены. Leaving сохраняет историю членства.

## Ошибки и gaps

Backend: 400 — некорректный JSON / неизвестные поля; 422 — входные данные; 401 — авторизация; 403 — права; 404 — ресурс; 409 — конфликт состояния; 429 — ограничение; 503 — недоступность; 504 — timeout. Тексты backend не передаются UI напрямую. Клиент связывает запрос с origin+account, отменяет устаревшие операции и очищает память при выходе / смене профиля / истечении сессии.

Не обнаружены API удаления встречи, изменения title / waitingRoomEnabled существующей встречи, dashboard, total count, отдельного календаря, profile email/password/avatar update. Capabilities не содержит флагов meetings/calendar/profile. У POST create нет idempotency key: сетевой неопределённый результат требует перечитывания и явной проверки пользователем перед новым созданием; автоматического повторения POST нет. При 404/405/410 неизвестного маршрута Android сообщает отсутствие API, не скрывая ошибку под пустым списком.

Проверки этапа: реальные handlers+JWT+PostgreSQL на изолированном временном staging, HTTP контракт и permissions/lifecycle/pagination/month bounds; Android adapter wire/auth isolation; ViewModel cancellation/filter race/dedup/uncertain POST; Compose phone/tablet формы, error/loading, календарь, детали и профиль.

Изолированный staging: PostgreSQL 14 в отдельном datadir `.audit/meetings-stage05/pg-data`, loopback port 15455; HTTPS fixture 18455, backend Go 1.26.9. Применены все 33 реальные миграции. `cmd/android-stage05-fixture/main.go` в audit checkout связывает настоящие Gin routes, auth/conference handlers, Argon2, JWT, durable sessions и PostgreSQL repositories. Возможности возвращает штатный platform Service/Handler; `/version` — штатный buildinfo Handler. Redis, SFU и media workers не запускались и не проверялись. Production и пользовательский backend checkout не менялись.

`backend_contract_check.py` выполняет 60 проверок настоящего HTTPS API: создание, расписание, lifecycle, приглашение/членство, история, профиль, запреты ролей, cursor и границы месяца, отозванная сессия. Результат — `docs/android/evidence/05/staging-backend-contract.json`; значения email/password/token/meeting IDs не попадают в evidence. Временные credentials и TLS private keys находились только в закрытом audit-каталоге, вне приложения; после тестов серверы остановлены, keys/JWT/credentials/БД удалены, порты освобождены (`staging-cleanup.json`).

Повторяемая fixture сохранена в `testing/stage05` с README. Независимый start → 60 HTTPS проверок → stop/remove выполнен на новом Git clone и новых портах (`staging-reproducibility.json`). Для следующего запуска используется отдельный закрытый `.audit/stage05-fixture`, чтобы не затронуть build/test evidence этого этапа.
