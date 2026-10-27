# MeetSpace Android — безопасность, 27.10.2026

Область: текущий рабочий Android checkout, включая сохранённые изменения уведомлений и каталога записей. Это анализ кода, локальные негативные тесты и проверки на отдельных тестовых сервисах; не penetration test боевого сервера. Начальная точка и неизменённые ограничения — [baseline](ANDROID_AUDIT_BASELINE.md).

## Подтверждённые проблемы

| ID / приоритет | Поверхность и доказательство | Исправление / проверка |
| --- | --- | --- |
| MEDIA-001 / P1 | Камера/микрофон могли оставаться активными, пока actor ожидает REST/SDP/presence. Два gated-теста на исходном APK не дождались освобождения за3s. | Приоритетное отключение с отменой текущей операции, сериализация cleanup и защита от устаревших команд. Итоговые проверки — в test report. |
| APP-005 / P1 | Запоздалый результат permission launcher не был привязан к встрече/аккаунту/foreground. | Одноразовый target guard и invalidation на STOP/disposal; 5 JVM + 3 native lifecycle регрессии прошли. Обычный PAUSED permission dialog подтверждён positive control. |
| APP-006 / P2 | 403/404 optional pins/search/context/attachment ошибочно стирал весь доступный чат. Legacy fixture воспроизвёл pins404 при успешном detail/history. | Ошибка очищает соответствующую проекцию; доступ чата перепроверяется canonical endpoint. Только подтверждённый отказ detail/history очищает чат. Основная авторизация не ослаблена. |
| APP-002 / P2 | После403/404 refresh встречи UI сохранял ранее загруженные подробности/историю/приглашение. Это stale-cache issue, не доказанный серверный IDOR. | Очистка отозванной проекции встречи, сохранение кэша только при сетевых ошибках. JVM-регрессии detail/self/history. |
| APP-001 / P2 | Отмена принятого SAF-экспорта до старта LAZY coroutine обходила catch и оставляла новый пустой документ. | Cleanup привязан к завершению Job; успешный документ не удаляется; application ContentResolver не удерживает Activity. Регрессии до старта/при очистке VM. |
| DATA-001 / P2 | `phase_backup` читал весь PostgreSQL dump в память трижды. Размер базы не ограничен; на4GB VPS возможна нехватка памяти. Production OOM не воспроизводился. | Потоковый hash и stdin файла для pg_restore;8 новых локальных регрессий, подписанный bundle переподписан прежним ключом, trust root не изменён. |

APP-003 (материалы отдельных дорожек) и APP-004 (восстановление громкости) — надёжность, описаны в summary. Источники до исправления: baseline и baseline-data/media-findings.md.

## Проверенные защитные границы

- Manifest и собранные debug/release/AAB: backup/data extraction отключены, cleartext запрещён, release не debuggable; камера/микрофон optional. Пользовательские ScreenShare/FCM services не экспортированы. Launcher Activity экспортирована по назначению. Firebase fallback service удалён; FCM выключен без конфигурации. Нет broad storage permissions. WorkManager/Firebase добавляют boot/wakelock/C2DM permissions, это учтено в merged manifest.
- Нет WebView или обработчика произвольных внешних deep links. Notification PendingIntent явный, immutable; входящий action открывает центр уведомлений, не выполняет привилегированные серверные операции.
- OkHttp использует системные TLS/hostname checks, не следует redirect и не кеширует ответы; нет trust-all или credential/body logging. Cookies ограничены точным HTTPS origin и auth routes. Файлы по подписанным URL получают отдельные запросы без bearer/cookies приложения.
- Сессии: Android Keystore AES-256-GCM, scope AAD, приватное no-backup хранилище; токен не лежит в DataStore профилей. Смена аккаунта/сервера и logout закрывают WSS/recording leases и отменяют scoped UI requests. Результаты устаревших запросов отбрасываются generation/ticket guards.
- Вложения: SAF, лимиты размера/типа, приватные временные данные; запись через ContentResolver, очистка byte buffers. Записи: метаданные без публичных capability URLs; выдача ограниченных lease, stream/hash/size checks и закрытие при смене scope. Обложки: encoded4MiB, cache8MiB, decode до1024px, максимум2 параллельных загрузки; нет постоянного bitmap cache на диске.
- Уведомления: системный канал, DND/silent не обходятся, детерминированное account-scoped подавление дубликатов. FCM end-to-end не настроен, поэтому доставка в закрытое приложение не подтверждена.
- WebRTC: signaling auth, ограничения протокола/ICE/SDP и владение ресурсами. TLS/DTLS-SRTP защищает транспорт; это не end-to-end encryption от SFU. Наличие TURN credentials ожидаемо для клиента; секреты не включаются в диагностические отчёты.
- SSH: проверка host/port public key и changed-key отказ, inspect заканчивается до аутентификации; fixed commands + валидация/quoting, секретные payload через stdin; secrets уничтожаются в finally, blocking IO имеет deadlines и ограниченный executor. Installer проверяет подпись, hash, закреплённый commit/images, lock/journal/ownership paths; пользователь подтверждает изменения пакетов/ACME. Реальный VPS в этом аудите не переустанавливался.

## Зависимости, секреты и выпуск

Свежий запрос [OSV exact-version API](https://google.github.io/osv.dev/api/) по178 Maven coordinates releaseRuntimeClasspath: **0 совпадений advisories**, все страницы получены. Это не гарантия отсутствия CVE: база не покрывает полностью JNI/WebRTC third-party sources, build plugins, OS и будущие advisories. Точные входы/ответы сохранены локально; безопасная сводка — [osv-summary.json](evidence/osv-summary.json). Major upgrade библиотек без установленной причины не выполнялся.

Gradle wrapper имеет закреплённый SHA256, CI actions закреплены commit SHA и работают с `contents: read`, без production secrets. Отдельного Gradle verification-metadata.xml нет; автоматическая проверка checksum всех Maven артефактов **не настроена**. Не выдаём повторную загрузку/успешное dependency resolution за integrity verification. Перед включением [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html) нужен проверенный эталон checksum, а не слепое доверие текущему cache.

Regex scan1433 tracked/nonignored files до8MiB: найденные10 private-key headers принадлежат валидатору/синтетическим тестам, живые ключи/токены по проверенным шаблонам не обнаружены. Это ограниченный scan, не анализ полной Git history. Artifact verifier отдельно проверил отсутствие известных приватных маркеров/fixture данных в APK/AAB; корпус1 локальный input/17 needles, не полный каталог секретов. `.audit`, ключи JKS/keystore и конфигурация Firebase игнорируются Git. Приватный ключ installer использован только локально, совпадение public key проверено; в bundle остаётся только публичный ключ.

Лицензии Inter/OFL, JSch и WebRTC/wrapper включены в assets; упаковка не удаляла их. Полный автоматический лицензионный compliance review178 пакетов и юридическое заключение не выполнялись.

Release APK/AAB собираются с R8, но постоянная подпись не передана через четыре MEETSPACE env-переменные. Нельзя публиковать debug APK как совместимое обновление без совпадения certificate. На отдельном API36 ARM64 AVD с фактическими16384B pages и выключенным compatibility mode прошли native loader smoke (3 библиотеки/factory) и запуск minified release с временной тестовой подписью до первого экрана. Это не проверка всех звонков/устройств. ELF PT_LOAD и ZIP alignment сами по себе недостаточны; см. [официальное руководство Android](https://developer.android.com/guide/practices/page-sizes). Подписанный пользовательский релиз/Google Play upload, FCM и hardware compatibility остаются отдельными условиями выпуска.

Дополнительный artifact scan промежуточной сборки до APP-006: 22 различных секретных значения из8 файлов только одноразовых fixtures проверены по распакованным ZIP entries debug/release APK и AAB; совпадений0. Это exact-string scan ограниченного корпуса, не доказательство отсутствия неизвестных секретов.

Итоговый packaging verifier после APP-006 повторён (`final2-artifact-verification.json`): debug/release APK/AAB PASS, существующий exact-string corpus17 markers без совпадений. Временные credentials и отдельные базы/containers четырёх fixtures после проверок удалены; боевые сервисы не затронуты.
