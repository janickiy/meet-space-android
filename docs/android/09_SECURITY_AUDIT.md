# Этап 09 — аудит безопасности и данных

Дата: 2026-10-11. Проверен сохранённый baseline Android 0.8.0 (7), выбранные зависимости `app:releaseRuntimeClasspath` и clean backend `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`. Это ограниченный аудит исходников, manifest, упаковки и публичной базы зависимостей; он не является penetration test production. Production-сервер не использовался, Gradle/эмулятор этим исполнителем не запускались.

## Подтверждённое замечание до исправления

**SEC-09-01, P2, комплект лицензий: основной BSD notice JSch не попал в release APK.**

Фактически выбран `com.github.mwiede:jsch:2.28.8`. Его исходный JAR содержит `META-INF/LICENSE.txt` с copyright JCraft 2002–2015 и BSD условиями. В сохранённом release APK ни этого entry, ни полного эквивалента текста нет. Присутствующие `META-INF/LICENSE.JZlib.txt` и `META-INF/LICENSE.jBCrypt.txt` относятся к другим составляющим и не заменяют основной notice. WebRTC aggregate notices и Bouncy Castle MIT notice присутствуют.

Воспроизведение: открыть exact-version JSch JAR из Gradle cache, прочитать `META-INF/LICENSE.txt`, затем перечислить entries и сравнить полное содержимое с неисполняемыми entries `.audit/stage09/baseline-app-release-unsigned.apk`. SHA JAR, notice и APK, а также результат сравнения сохранены в [security-baseline.json](evidence/09/security-baseline.json). Первичный источник: [опубликованный JSch 2.28.8 JAR](https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/2.28.8/jsch-2.28.8.jar).

После фиксации baseline добавлен точный notice в `app/src/main/assets/licenses/jsch-LICENSE.txt`: SHA-256 `9c3537ab0b485b391b887b95fdff761de3dd3273a6931e8a1942dde4d2093cc5`, без изменения байтов исходного JAR entry. В `testing/stage08/verify_artifacts.py` добавлена отдельная проверка pinned notice в APK/AAB; она не зависит от присутствия исходного asset и поэтому обнаруживает также удаление файла из обоих мест. Функция `required_license_results` и общий `checks` доступны для повторного использования в этапе 09. **Исправлено в исходниках; проверка новых собранных артефактов ожидает root.** До неё замечание не считается закрытым. Это дефект комплектации выпуска, а не подтверждённая runtime-уязвимость. Исторические evidence baseline не изменены.

## Проверенные границы

| Область | Что установлено по baseline | Предел вывода |
| --- | --- | --- |
| HTTPS/TLS | `ServerOrigin` принимает только канонический HTTPS origin без userinfo/path/query/fragment; OkHttp использует обычную проверку цепочки и hostname; HTTP/HTTPS redirects выключены | Новое сетевое MITM испытание в этом подаудите не выполнялось |
| Авторизация | Bearer строится только для выбранного origin, path/query кодируются отдельно; cookie host-only/Secure/HttpOnly и ограничена `/api/v1/auth`; переходы аккаунта/сервера сериализованы mutex | Серверные роли и права остаются ответственностью backend; DTO validation не заменяет ACL |
| Refresh/logout | Один refresh на 401, безопасное чтение повторяется; изменения после refresh требуют явного повтора; logout очищает RAM и пытается независимо удалить credential и ссылку профиля, даже если remote logout не подтверждён | Отзыв ранее выданной signed URL в object storage этим не обеспечивается |
| Хранение | Refresh credential AES-GCM с AAD origin/account и ключом Keystore, private `noBackupFilesDir`; password/access token не сохраняются в настройках; passwords не используют `rememberSaveable` | Managed heap может содержать временные неизменяемые строки; утверждения о гарантированном затирании всех копий нет |
| Chat IDOR/file scope | IDs canonical UUID; вложения и сообщения проверяют conversationId; download endpoint конструируется из текущего scope; authenticated content URL обязан совпасть с точным относительным путём | Прочитан backend `DownloadAttachment`: membership, связь attachment/conversation, доступность сообщения и history cutoff; новые live запросы не выполнялись |
| Записи/материалы | Проверяются conference/record/file/transcript IDs, тип/MIME, размеры и pagination. Signed URL остаётся внутри opaque lease; открытие заново требует detail API. Logout закрывает и открытые, и ещё не открытые leases | Backend выдаёт URL записи на 24 часа; отзыв членства не отзывает уже выданную storage capability до expiry |
| Файловый transport | Запрос к signed storage не содержит backend Authorization/cookies; redirects/cache отключены; проверяются размеры/Range, SHA при полном экспорте и наличии checksum | Просмотр отдельных ranges не является проверкой SHA всего файла. Provider может отказать в удалении частичного экспорта |
| SAF/интенты | Импорт/экспорт через системный document picker; нет filesystem path resolution, persistent URI grants или broad storage permission. Notification PendingIntent immutable; открывает общий inbox без private URL или текста | Контент явно экспортированного пользователем документа остаётся вне sandbox и не удаляется при logout |
| Уведомления/FCM | FCM service disabled, fallback service отсутствует; auto-init/delegation/analytics/BigQuery metadata false. Реализованные hints не дают прав: scope проверяется, сведения перечитываются REST | Firebase-проект отсутствует; token rotation, cold push, gateway и удалённый revoke в реальной FCM среде **not tested** |
| SSH | В просмотренном transport `StrictHostKeyChecking=yes`, отдельный read-only host key repository, exact host/port pin и сравнение ключа; credentials не записываются профилем | Подробные SSH/installer испытания и ресурсы — отдельная часть итогового аудита |
| Логи | Не найдено логирования bearer/cookie/password/signed URL в просмотренных production sources; media warning выводит только имя класса исключения и место вызова | Это source review, не полный динамический анализ всех SDK/системного logcat |

В просмотренных границах не подтверждены критический auth bypass, пересылка bearer в object storage или новый IDOR. Это не утверждение об отсутствии всех уязвимостей.

## Фактический release manifest

Прочитан manifest непосредственно из сохранённого release APK через Build Tools 35 `aapt2`, а не только `src/main/AndroidManifest.xml`: [raw dump](evidence/09/security-baseline-manifest.txt), [структурированные поля и SHA](evidence/09/security-baseline.json). `allowBackup=false`, `usesCleartextTraffic=false`, `debuggable` отсутствует/false, instrumentation отсутствует. Backup/data extraction source rules исключают app-private cloud/device-transfer domains. Камера/микрофон optional, broad storage/contact/location permissions нет.

Экспортированы ровно следующие компоненты:

| Компонент | Назначение/защита |
| --- | --- |
| `com.meetspace.app.MainActivity` | Launcher; внешняя команда `OPEN_INBOX` меняет навигацию, не предоставляет auth или доступ к произвольному URL |
| `com.google.firebase.iid.FirebaseInstanceIdReceiver` | Требует `com.google.android.c2dm.permission.SEND` |
| `androidx.work.impl.background.systemjob.SystemJobService` | Требует `android.permission.BIND_JOB_SERVICE` |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver` | Требует `android.permission.DUMP` |
| `androidx.profileinstaller.ProfileInstallReceiver` | Требует `android.permission.DUMP` |

Оба собственных service и все providers не экспортированы. Собственная dynamic receiver permission имеет signature protectionLevel `0x2`. Произвольный `content://`/web deep link manifest не принимает. Runtime попытки вызова компонентов из отдельного непривилегированного APK в этом подаудите **not tested**; `adb shell` не заменяет такого отправителя для permissions DUMP.

## Зависимости: фактические результаты

Источник — `.audit/stage09/release-dependencies.txt`, сохранённый root до исправлений. Выделено **178 уникальных выбранных Maven coordinates**; constraint-only строки не включались. Это число модулей resolution graph, включая BOM/metadata, а не число библиотек, чьи байты остались после R8.

- [OSV evidence](evidence/09/security-osv.json): запросы `Maven group:artifact + exact selected version` к официальному `https://api.osv.dev/v1/querybatch`; **178 успешных ответов, 0 совпавших advisories**, pagination отсутствует. Протокол: [OSV querybatch](https://google.github.io/osv.dev/post-v1-querybatch/). Отсутствие совпадения означает только отсутствие записи для этих координат/версий в ответе базы на дату запроса. Это не «0 CVE»: embedded native WebRTC/кодеки, shaded code, непубличные дефекты и Android platform вне такой проверки.
- [License inventory](evidence/09/security-licenses.json): для 178 координат прочитаны exact-version POM, сохранены SHA и public source URLs; 175 имеют прямое declaration, 3 Guava modules используют declaration exact parent POM. Встречаются Apache 2.0, BSD variants, ISC, Bouncy Castle license, Android SDK license. Классификация основана на publisher metadata; не является юридическим заключением или доказательством выполнения всех notice obligations.
- WebRTC native third-party notices включены отдельным aggregate asset (788358 bytes baseline); SDK wrapper MIT notice также включён. Bouncy Castle MIT текст обнаружен внутри APK. Для JSch найден SEC-09-01 выше.

Обновления зависимостей на основании одного возраста версии не предлагаются. Сопоставление native commit/Chromium advisories и лицензионный аудит каждого embedded native subcomponent остаются отдельными задачами; Maven OSV результат их не покрывает.

## Проверки, которые должны подтвердить исправления

Production исходники в ходе baseline security review не менялись. Имеющиеся tests `FoundationRepositoryTest`, `MessagingRepositoryTest`, `RecordingsRepositoryTest`, `PushHintTest` и ViewModel tests просмотрены как покрытие; их результаты этапа 08 не выдаются за новый запуск этапа 09. Актуальные Gradle/lint/instrumented/live результаты вносит root в [FINAL_QUALITY_AUDIT.md](FINAL_QUALITY_AUDIT.md).

Для закрытия SEC-09-01 нужны exact asset в собранных debug/release APK и AAB, совпадение SHA с исходным JAR и сохранение уже включённых WebRTC notices. До распространения необходим общий список компонентов/лицензий для фактически поставляемой сборки, privacy policy и настоящая release signing configuration. Фоновый FCM нельзя считать проверенным до появления конфигурации и gateway.

Подготовлены portable stage09 verifier и CI: [инструкция, scope и ограничения](../../testing/stage09/README.md). Пять Python regression tests проверили exact/missing/changed notice, AAB path и запрет перезаписи historical reports; workflow прошёл YAML/bash syntax и actionlint1.7.12 без shellcheck/pyflakes. GitHub Actions и новая Android artifact verification этим подаудитом не запускались.
