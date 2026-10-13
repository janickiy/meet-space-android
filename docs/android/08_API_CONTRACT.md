# Этап08 — фактический контракт записей и материалов

Проверен checkout backend `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. Все пути ниже имеют префикс `/api/v1`. Авторизация — текущий scoped Bearer-сеанс API. Проверки выполнялись на отдельном PostgreSQL/Redis/MinIO стенде, production не использовался.

| Операция | Фактический маршрут и ответ |
|---|---|
| Выбор встречи | `GET /me/conferences`, непрозрачный `nextCursor`; `view=past/active/upcoming`, фильтр scope |
| Записи встречи | `GET /conferences/{cid}/recordings?limit=20&offset=0`, `items`; серверного total/nextCursor нет |
| Одна запись и обновление ссылок | `GET /conferences/{cid}/recordings/{rid}`, `item` |
| Расшифровка | `GET /conferences/{cid}/recordings/{rid}/transcript`, nullable `item`, `enabled`, `canRetry`, `providerMode` |
| Сегменты | `GET .../transcript/segments?limit=100&offset=0`, `items,total,limit,offset`; limit1–200, offset0–100000 |
| Итоги | `GET .../summary`, nullable `item`, `enabled`, `canRegenerate`, `providerMode` |
| Повтор обработки | `POST .../transcript/retry` и `POST .../summary/regenerate`; отдельные owner/cohost права, cooldown/бюджеты, disabled provider→503. Android08 их не запускает |

Общей коллекции записей, отдельного download endpoint, удаления/переименования записи и редактирования материалов в проверенном API нет. Веб-клиент тоже сначала выбирает встречу, затем читает её записи. Нельзя выдавать записи каждой встречи фоновым N+1 обходом или изобретать глобальный endpoint. Legacy `/api/v1/records`, `/api/records`, `/debug/records` и `/debug/webrtc-smoke` отвечают410.

Карточка записи содержит `uuid` (внешний UUID), `conferenceId`, `mode`, `status`, даты/длительность и `files`. Внутренние `id`/`recordId` числовые; идентификатор файла для клиента — `file.uuid`. Файлы имеют `fileType`, `fileName`, `mimeType`, nullable `sizeBytes`, `durationSec`, `checksumSha256`, `url`. Backend также возвращает storage keys, технические события и debug-файлы: эти поля не являются продуктовым интерфейсом. Android хранит только безопасные метаданные, выбирает ready final_mp4/final_audio и необходимые preview; debug_log, bucket, objectKey, worker/event diagnostics и signed URL в UI не показываются.

`Read/List` проверяют актуальное членство: `admissionState=admitted` и статус `joined` либо `left`. Вышедший допущенный участник сохраняет историю; outsider/kicked/rejected не получает её. Пара cid/rid проверяется совместно. Материалы дополнительно требуют готовую и не удалённую запись; summary привязан к текущему поколению transcript. Отсутствующий transcript имеет200 `item:null`; запрос его segments —404, не подставной пустой успех. Не готовый transcript даёт409 для segments. Материалы имеют `Cache-Control: private, no-store` и `X-Content-Type-Options: nosniff`.

`enabled` описывает доступность **новой обработки**, а не запрет чтения сохранённых материалов. Настоящий backend возвращает `item.status=ready` и исторические segments/summary даже при `enabled=false` и `providerMode=noop`. Готовый материал должен оставаться доступным; disabled-сообщение относится к отсутствующему результату/новой генерации.

## Signed media и ограничения отзыва

Backend `recorder.Service.presignedURL` выдаёт URL приватного S3/MinIO на24ч (`X-Amz-Date`, `X-Amz-Expires=86400`); отдельного `expiresAt` в DTO нет. Ссылка может иметь другой origin. Storage запрос получает только разрешённую HTTPS ссылку/Range, без API Authorization, cookies или автоматических redirect. Настоящий MinIO подтверждает bytes, SHA256, `206 Content-Range`,403 для unsigned/tampered/expired URL.

Уже выданный signed URL является bearer-доступом и продолжает работать после исключения участника до собственного expiry. Новый REST запрос записи/материалов после kick запрещён. Это проверенный backend gap: Android заново запрашивает card/access перед просмотром/экспортом и закрывает scoped stream при выходе/смене сервера, но не может отозвать ранее выданную S3 подпись. Для немедленного server-side отзыва нужен авторизованный media proxy либо иная политика backend.

Отдельная граница доверия — потоковый доступ к большому файлу. Новый export проверяет актуальные права, размер/checksum при наличии метаданных и пишет в выбранный SAF document без копии всего видео в памяти; отмена/ошибка удаляет частичный новый документ. Скачанный пользователем законченный файл остаётся у него после выхода.

## Настройки

Доступны существующий PATCH `/auth/me` (displayName), GET/PUT `/notifications/preferences`, локальная тема и управление сохранёнными серверами, системные Android разрешения. Фоновые push требуют отдельной реальной Firebase/gateway конфигурации; её нет, поэтому выключены. Произвольных server settings, интеграционных секретов или нового account API этот этап не создаёт.

Источники: `internal/transport/http/conference_recording_routes.go`, `content_routes.go`, `retired_recording_routes.go`; `internal/usecase/recordings/service.go`; `internal/usecase/recorder/conference_reader.go`, `service.go`; `internal/infrastructure/postgres/conference_recording_repository.go`, `content_repository.go`; `internal/domain/records/{dto,models}.go`; `internal/domain/content/content.go`; `frontend/src/pages/RecordingsPage.tsx` в указанном backend commit.
