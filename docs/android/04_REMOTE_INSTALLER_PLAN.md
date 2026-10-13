# Этап 04 — план до реализации

Дата: 2026-10-11. Задание: автоматическая Docker-установка из Android по SSH. Пользователь выбрал закреплённый commit со сборкой на VPS, поскольку upstream не публикует tags/releases/готовые образы. Новый baseline: `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69` из отдельного чистого `.audit/meet-space-stage04`; прежний checkout и пользовательский backend не изменяются.

## Объём

1. Версионированный Python 3 runner в `installer/v1`, доставляемый из APK через проверенный SSH. Manifest рецепта подписан отдельным ключом поставки, фиксирует backend commit, source archive hash, Dockerfile/base-image/infrastructure digests. Backend tag отсутствует и не выдумывается. Сборка на VPS явно указана в плане; полученные image IDs фиксируются перед запуском и при resume повторно проверяются.
2. Первая матрица: новый выделенный Ubuntu 24.04, systemd, Python 3/OpenSSL, root или noninteractive sudo; amd64/arm64, от 4 CPU, 8 GiB RAM и 30 GiB свободного диска. Поддержка shared host, in-place upgrade неизвестной установки и других distro не подразумевается. Реальная VM-проверка — arm64.
3. Read-only plan с hash/сроком, hostname, domain, public IPv4, ACME email, release/runner fingerprint, каталогом `/opt/meetspace`, портами, ресурсами и действиями. Отдельное явное подтверждение apply и установки Docker/Compose, если их нет. Повторная проверка preconditions под remote lock. Соседние процессы, firewall rules и volumes автоматически не удаляются/не останавливаются.
4. Подготовка directory/managed marker, Docker из подписанного официального apt repository с закреплёнными версиями, verified source snapshot, сборка, генерация серверных secrets, изолированная Compose network/volumes, явные DB migrations с backend ledger, reverse proxy, ACME HTTPS/WSS, Coturn с auth/private peer restrictions, проверки readiness и ожидаемого `/version`. DB/Redis/RabbitMQ/S3 console не публикуются.
5. Durable job state/events + `flock`, detached systemd execution, стабильные job ID/plan hash, query-before-retry. SSH loss означает потерю соединения клиента; safe cancellation на границе фаз, resume из checkpoint. Не обещать rollback миграций: новые пользовательские volumes/config не удаляются; backup/checksum перед миграцией, recovery instructions при отказе. Уже завершённая managed установка возвращает actual status, повтор apply не пересоздаёт secrets/данные.
6. Android: план/подтверждение/фактические фазы/ошибки/отмена/resume. SSH trust и credentials остаются отдельными от REST; credentials только в памяти активной сессии, metadata последнего job без secrets сохраняется для reconnect после process death.
7. Unit/Compose/remote integration: success на отдельной Linux VM, SSH drop, disk full, occupied ports, DNS failure, retry/already installed, отказ при подмене signature/hash/host key, очистка secrets/logs. Затем debug/release сборка, отчёт с фактическими ограничениями и cleanup.

## Gaps и решения

- Upstream `init-meeting-host.sh` привязан к production-host; не используется. Существующие Compose, migrations, proxy routes, TURN rules и health endpoints — основание нового выделенного install layout.
- Upstream image release gate рассчитан на заранее собранные и проверенные артефакты. Выбранный пользователем source-build профиль оформляется отдельно; не подставлять фиктивные approvals/security scan results. Source/base digests не означают hermetic build: apk/apt runtime packages проходят подписи своих репозиториев, итоговые образы фиксируются после сборки. Это ограничение раскрывается в плане/отчёте.
- Формального upstream release tag нет. Версия installer recipe отделена от backend commit; manifest не утверждает наличие опубликованного backend релиза. Подпись локальной поставки не выдаётся за подпись существующего upstream publisher.
- Public ACME требует настоящий DNS и доступность 80/443. Production-данные DNS/SSH не предоставлены; реальный VPS не трогаем. Для интеграционного success допустим отдельный test ACME CA с явным доверенным корнем внутри disposable VM; выпуск публичного сертификата этим не доказывается, production trust-all/TLS bypass запрещены.
- Supported fresh-host layout владеет 80/443, 3478 tcp/udp, 5349 tcp, 50010 tcp/udp и 49160–49259 udp. Конфликт или неизвестная существующая установка блокирует apply. Docker может обходить ufw при публикации портов; этот риск и внешний cloud firewall отображаются в плане.
- Отмена UI не убивает миграцию/apt посередине. Запрос отмены сохраняется на сервере и исполняется на безопасной границе; данные сохраняются. Смена release/layout для существующего managed install требует будущего отдельного upgrade-плана.

## Источники

- Фактические `docker-compose.production.yml`, `.env.production.example`, `scripts/release`, `dockers/production`, `dockers/turn`, migration ledger и HTTP routes в baseline выше.
- [Docker Ubuntu installation](https://docs.docker.com/engine/install/ubuntu/): официальный signed apt repository и ограничения firewall; convenience curl|bash не применяется.
- [ACME HTTP-01](https://letsencrypt.org/docs/challenge-types/): проверка домена через доступный снаружи HTTP.
- [Upstream releases](https://github.com/janickiy/meet-space/releases): опубликованные релизы отсутствуют на момент проверки; `git ls-remote --tags` пуст.

После этапа 04 остановиться. Production deployment, publication/tag/push и следующий этап не выполнять автоматически.
