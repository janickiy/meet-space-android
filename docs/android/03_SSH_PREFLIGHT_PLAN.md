# Этап 03 — план до реализации

Дата: 2026-10-11. Основание: запрос пользователя и CODEX_ANDROID_03_03_SSH_PREFLIGHT.md. Этап ограничен SSH и read-only preflight; установка, изменение firewall/packages/Docker и production VPS не входят в работу.

## План

1. Добавить отдельный модуль `core:ssh`, не смешивая SSH credentials и trust с REST-сессией. После обзора upstream выбрать поддерживаемый mwiede/JSch с современными алгоритмами и Android-совместимым crypto provider; зафиксировать версии и фактическую проверку на Android.
2. Мастер self-hosted: host/IP, port, SSH user, domain, password или private key через Storage Access Framework, passphrase. Учётные данные только в памяти операции, без файлов/настроек/логов и persistable URI permission.
3. TOFU + pinning: отдельный handshake без credentials, SHA-256 отпечаток и проверка по панели VPS/администратору, явное подтверждение. Хранить только публичный ключ host+port в отдельном DataStore. Повторное соединение должно строго совпасть с pin; смена ключа блокирует auth без автоматического overwrite.
4. Выполнить ограниченный статический read-only shell probe с timeout, лимитом stdout и закрытием SSH при отмене. Не выводить произвольные remote logs. Интерпретировать OS/arch, CPU/RAM/disk, root/noninteractive sudo, Docker/Compose, занятые порты, domain DNS, TLS, существующие каталоги/проекты и firewall как отдельные проверки с PASS/WARN/BLOCKED/UNKNOWN.
5. Проверить модели/валидацию/парсер/host-key policy mock SSH тестами; подготовить изолированную Linux VM для реального SSH, ключа с passphrase, неверного auth, смены host key и отмены. Проверить мастер Compose тестами и на Android-эмуляторе. Обновить APK и итоговый отчёт с фактическими результатами.

## Gaps и решения

- Android stage 02 работает; пункт «Развернуть свой сервер» пока информационный. Заменить его мастером, сохранив авторизацию существующего HTTPS сервера.
- Backend checkout `c861ec8…`: generic bootstrap/installer runner отсутствует; init-host привязан к конкретному hostname/окружению. Не запускать этот script, не представлять успешный preflight готовой установкой.
- Production Compose использует 443/tcp, 3478/tcp+udp, 5349/tcp, 49160–49259/udp и 50010/tcp+udp. Проверять также 80/tcp как будущий HTTP ACME/proxy риск. Фактические внешние firewall/NAT правила нельзя доказать локальным `ss`; показывать границу диагностики.
- Обоснованной универсальной resource/distro матрицы installer нет. Результаты Linux Ubuntu/Debian amd64/arm64 и порогов ресурсов являются предварительной оценкой для будущего плана, а не обещанием вместимости всей production конфигурации.
- Нет SSH credentials от пользователя и разрешения на изменение production. Использовать disposable VM. Не пробовать HTTP пароль как SSH пароль.
- SSHJ также просмотрен; текущие зависимости/Java требования сложнее для minSdk 26. Выбранный JSch требует отдельно подтвердить поддержку Ed25519/OpenSSH/encrypted keys на Android, а не считать Java version доказательством.
- DNS/TLS могут быть ещё не настроены до установки: отсутствие сертификата или несовпадение DNS — видимый риск, не fake success и не TLS bypass. Sudo проверяется только `sudo -n`, без передачи пароля в sudo stdin.

## Граница следующего этапа

Завершить после preflight. Никакой кнопки, исполняющей install/apply, до отдельного этапа с конкретным планом, trusted release manifest, backup/rollback и явным подтверждением пользователя.

## Источники обзора

- [mwiede/JSch upstream: Java 8, современные алгоритмы, Bouncy Castle и RSA/SHA2](https://github.com/mwiede/jsch).
- [JSch 2.28.7 release](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.7).
- При проверке Maven metadata обнаружены более свежие security hardening releases; для фактической сборки выбран [JSch 2.28.8 от 10 октября](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.8) и [Bouncy Castle 1.86 от 11 сентября](https://www.bouncycastle.org/download/bouncy-castle-java/). Остальной набор stage 02 не обновляется.
- [SSHJ releases](https://github.com/hierynomus/sshj/releases).
- Фактический `docker-compose.production.yml`, `.env.production.example`, scripts/release и [модель угроз](INSTALLER_THREAT_MODEL.md) из этапа 01.
