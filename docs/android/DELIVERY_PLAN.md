# MeetSpace Android — план поставки после аудита

11.10.2026. Выполнен только этап 01. Этот документ — план последующих поручений, не автоматически начатая реализация. Основание: [аудит](00_AUDIT.md), [контракты](API_MATRIX.md), [installer threat model](INSTALLER_THREAT_MODEL.md).

## 1. Что уже получено

Созданы четыре отчёта в `docs/android/`. Проверен GitHub commit `c861ec882633071da74c6825a3dbf2a19c1e32ed`, текущий локальный вариант 01 дизайна, API/WS/SFU, 33 up-миграции, Compose/deployment scripts и публичные endpoints сервера. Нативного приложения пока нет. Изменений production/backend/макета, deployment, migrations, push или публикации не выполнялось.

Основа UI: [четыре вкладки локального дизайна][design]. Основа данных: [зарегистрированные серверные маршруты][wiring]. Нельзя подменять недостающую функциональность макетными данными или fake success.

## 2. Приоритеты и зависимости

| Этап | Результат и scope | Условие завершения |
|---|---|---|
| **01. Аудит — завершён** | Документы, матрица implemented/partial/absent, риски и архитектура | Отчёт доступен, claims привязаны к исходникам, ограничения явно указаны |
| **02. Основа и server/auth vertical slice** | Gradle/Kotlin/Compose/Material3, version catalog, базовые модули, вариант 01 shell, выбор HTTPS origin, version probe, login/me/refresh/logout, guest data model | Собирается APK; реальный вход в согласованный staging; cookie переживает restart; auth/cache не смешиваются между origin/accounts; TLS failures не обходятся; phone/tablet/resize smoke |
| **03. WebRTC feasibility gate** | Выбор libwebrtc, isolated media module, native permissions, conference join/WS/media offer-answer-ready, Opus/VP8, renderer/service lifecycle | Android↔web и Android↔Android media в staging, forced relay, 2/4 peers, late join, mute/camera, background/rotation и reconnect без дублирования peer; исправлен R02 или доказан согласованный временный adapter |
| **04. Встречи и календарь** | Главная/Звонки, создание/планирование/приглашение, история, waiting room, модерация, guest flow, deep links | Server-authoritative статусы и роли; нет двойного create после timeout; approve/deny/finish/revocation проходят e2e; нет fictitious missed calls |
| **05. Чаты и папки** | Direct/group, «Все/Личные/Новые», account WS, сообщения/ответы/вложения/read state, group roles/avatar, folders | Retry с тем же clientRequestId не дублирует сообщение; disconnect/rejoin восстанавливает snapshot; removed member теряет доступ и cache; обе ширины/IME/доступность |
| **06. Записи и уведомления** | Server recording control, список по встрече, Media3 playback, REST/SSE уведомления/preferences; optional push отдельно | Реальная запись проходит processing→ready и playback; ошибка/expiry URL не маскируются; background push принимается только после живого provider/device test |
| **07. Планшетная приёмка и выпуск клиента** | Device matrix, power/network/performance, accessibility, privacy, signing/CI, release evidence и rollback клиентской версии | Пройден набор приёмки ниже, нет критических открытых дефектов; release APK/AAB воспроизводим, подпись/ABI/dependencies зафиксированы |
| **08. Self-hosted installer — отдельный scope** | Verified SSH, inspect/plan/apply/status/resume, подписанные релизы, backup/rollback | Полная [модель угроз](INSTALLER_THREAT_MODEL.md), disposable VPS trials и проверенные backup/restore; не блокирует обычный client MVP |

Фундамент и media spike идут раньше детального переноса всех экранов: главный технический риск — совместимость native libwebrtc с текущей SFU-сигнализацией. Шаги 04–06 можно уточнить после результатов 03; порядок не означает автоматический запуск новых этапов. Сроки/стоимость без staging и device matrix не оценены.

## 3. Необходимые уточнения и решения

| Вопрос | Значение по умолчанию для планирования | Когда нужен ответ |
|---|---|---|
| Какой source соответствует production? | Сейчас неизвестно: `/version` сообщает недоступный в remote SHA | До интеграционной baseline этапа 02; запросить release manifest/source у владельца |
| Staging и тестовые аккаунты | Отдельные owner/member/guest + два Android устройства; не создавать тестовые встречи в production без согласованного сценария | До authenticated e2e |
| Репозиторий Android | Текущий `/Users/aleksandranickij/htdocs/Android`; сейчас это не Git repo | На старте 02 согласовать remote/CI, не инициализировать backend clone как мобильный проект |
| Minimum SDK/устройства | minSdk 26 предложен, arm64 physical + x86_64 emulator | До фиксации Gradle/libwebrtc и target SDK |
| Push / устройства без GMS | MVP realtime в foreground, push не обещан | До этапа 06 и обещания фоновых входящих |
| «Звонки» | Конференции и их история, без ring/accept/decline | Если нужен телефоноподобный сценарий — новый серверный контракт до реализации |
| Кэш/загрузки | Минимальный per-account cache; offline-записи не включены по умолчанию | До Room/download реализации и privacy review |
| Дизайн | Вариант 01 `meetspace-android-20261010`, текущее локальное состояние с четырьмя вкладками | На старте 02 сохранить immutable design snapshot/хеши |
| Installer | Отдельный поздний этап, обычный пользователь подключается к готовому серверу | До этапа 08 подтвердить ОС/layout/trust roots/resources |

Ни один unknown не должен превращаться в фиктивную готовность. Если сервер недоступен, экран показывает ошибку и повтор; если capability отсутствует, функция недоступна с причиной. Demo fixtures допустимы только в tests/previews и не включаются как production repository fallback.

## 4. Изменения backend, требующие отдельной реализации

1. **R02: TURN wire fields.** Пронести `iceTransportPolicy` и `iceExpiresAt` из [worker Result][media-ice] в [media.joined][media-joined], зафиксировать nullable/default семантику, добавить regression. Проверка — client candidate pair реально relay при force policy, не только наличие поля в JSON.
2. **Контрактные тесты.** У текущего дерева нет `_test.go`; восстановить isolated auth/personal/meeting/media tests. [CI][ci] и [frontend scripts][frontend] дают build/lint, а не e2e. Добавить схемы/golden fixtures для строкового sequence, cookie refresh, глобального ticket-only WS и guest scope.
3. **Discovery.** Расширение protocol/capabilities для поддерживаемых функций/версионной совместимости. Пока использовать explicit baseline; существующий [DTO][capabilities] не содержит достаточных признаков.
4. **Push.** Проверенный provider gateway и контракт token rotation/revoke. [Device endpoint][device] принимает android, но это ещё не доставленное уведомление. Ringing lifecycle — отдельное расширение при необходимости.
5. **По необходимости дизайна.** Общая библиотека записей, дополнительные profile actions, папки записей и административный installer API — отдельные запросы; не выдумывать пути в Android.

Ни одно из этих изменений не внесено аудитом. При server change обновить pinned contract baseline и повторить затронутые проверки; staging deploy/production deploy — отдельные операции с конкретным reviewable artifact.

## 5. Приёмочные испытания

| Область | Минимальный набор фактических проверок |
|---|---|
| Auth/security | Валидный/невалидный TLS; login/refresh/restart/logout/revoke; cookie path/domain; параллельные 401; 403 без refresh-loop; guest scope; origin switch; logs/backup без секретов |
| API/data | DTO parsing null/missing/unknown; string sequence; pagination/filter reset; idempotent message retry; 400/401/403/404/409/410/422/429/503; offline error без success |
| Meetings | Schedule timezone, invite, pending/approved/denied, roles, finish/cancel, разные physical connections одного пользователя |
| Chat | Direct+group в «Все», owner/admin/member, max members, reply/edit/delete, удаление из группы, attachment init/upload/finalize/expiry, прочтение с нескольких устройств, reconnect/refetch |
| Media correctness | Android↔web и Android↔Android; Opus/VP8; screen capture; offer/answer/ready; late ICE; stale callbacks; moderation; peer/track cleanup; звонок >1h для auth behavior |
| Network | LAN, Wi-Fi↔LTE, UDP blocked, TURN/TURNS relay-only, packet loss, server/media restart, временная потеря DNS/TLS, истечение TURN credentials |
| Android lifecycle | Rotation, split-screen/resize, background/foreground, permission denial/revoke, process death, camera contention, Bluetooth/wired/speaker, audio focus interruptions |
| Adaptive UI | Планшеты 7–13", phone compact, portrait/landscape, widths около 600/840/1200dp, compact height, font scale 200%, TalkBack, IME/insets, touch targets |
| Recording | Настоящий start/stop/processing/ready/failure; playback/seek; expiration presigned URL; session revocation; нет доступа к чужой записи |
| Notifications | Foreground SSE/global WS, unread/read, prefs, Android permission off; если push включён — Doze, killed app, token rotation, logout revoke, no-GMS policy |
| Performance | 2 и 4 участника как начальная матрица, затем advertised maxPeers; CPU/RAM/battery/thermal/bitrate; SDP limit; 16 KiB native page-size compatibility |
| Installer | Только если включён: threat-model negative cases + реальный restore на disposable VPS |

Целевые численные performance budgets установить после spike на named devices; не объявлять произвольный FPS/latency гарантией. Release evidence содержит устройства/OS/ABI, backend SHA/image digests, APK/AAR versions, сеть и длительность, ожидаемый/фактический результат. Screenshots макета не являются device test.

## 6. Что прошло в этапе 01 и чего не было

| Проверка | Результат |
|---|---|
| Go 1.26.9 `go test ./...` | Exit 0, 82 пакета, все `[no test files]` |
| `go vet ./...` | Успех |
| Frontend install/check | npm ci прошёл; initial Node 23 дал engine warning; lint/typecheck/Vite повторены и прошли на Node 24.19.0 |
| Dev Compose syntax | Успех `config --no-env-resolution --quiet` |
| Shell syntax | 19 файлов без ошибок |
| Публичный HTTPS/TLS/TURNS TLS | Проверены; `/version` mismatch зарегистрирован |
| Поиск production SHA в remote | Ошибка `not our ref`, происхождение сборки остаётся unknown |
| Authenticated API, Android, media e2e, TURN Allocate, запись, миграции, restore, нагрузка, security dependency scans | Не запускались |

Итоговые файлы этапа: `docs/android/00_AUDIT.md`, `API_MATRIX.md`, `INSTALLER_THREAT_MODEL.md`, `DELIVERY_PLAN.md`; служебный `.gitignore` исключает `.audit/` с checkout/dependencies/logs. Пользовательский backend checkout и дизайн не изменялись. Commit/push/PR не создавались.

## 7. Инструкция для следующего поручения

Выполнить **только этап 02**: сначала прочитать эти четыре документа и сохранить source/design baseline; получить согласованный staging/контракт, устранить неоднозначность production SHA для интеграции. Подготовить краткий plan/gaps до кода. Создать минимальный Kotlin/Compose каркас и реальный server/auth slice с защищённым session store; UI варианта 01, адаптация по окну. Не реализовывать весь backend заново, не подставлять mock success, не запускать installer/deployment. После сборки и проверок дать файлы, фактические результаты и ограничения, затем остановиться. Media spike — следующее отдельное поручение.

[capabilities]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/domain/platform/platform.go#L7
[ci]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/.gitlab-ci.yml#L46
[design]: /Users/aleksandranickij/htdocs/go-recorder/design/meetspace-android-20261010/screens.js:9
[device]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/integrations/service.go#L226
[frontend]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/frontend/package.json#L9
[media-ice]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/transport/mediaworker/handler.go#L689
[media-joined]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/usecase/media/controller.go#L402
[wiring]: https://github.com/janickiy/meet-space/blob/c861ec882633071da74c6825a3dbf2a19c1e32ed/internal/app/api.go#L162
