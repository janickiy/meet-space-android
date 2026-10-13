# Этап 08 — сборка, подпись и выпуск

Дата: 2026-10-11. `applicationId=com.meetspace.app`, `versionName=0.8.0`, `versionCode=7`, minSdk 26, target/compileSdk 36. Публикация приложения и создание постоянного ключа подписи в этот этап не входят. Этот документ описывает воспроизводимую подготовку; фактические проверки и ограничения находятся в итоговом отчёте этапа.

## Артефакты

| Артефакт | Путь от корня Android | Использование |
| --- | --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | Установка для разработки; Android debug-подпись |
| Release APK без конфигурации подписи | `app/build/outputs/apk/release/app-release-unsigned.apk` | Minified сборка для последующей подписи; напрямую не устанавливается |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` | Пакет для подписи/проверки/магазина; `adb install` не принимает AAB |
| R8 mapping | `app/build/outputs/mapping/release/mapping.txt` | Сохранить вместе с конкретным выпуском для разбора stack traces |

Если все четыре signing environment variables заданы, release APK/AAB подписываются заданным ключом; имя APK становится `app-release.apk`. Не смешивать подписанную и unsigned сборки при передаче артефактов. Для каждого выпуска сохранять hashes, номер версии, commit/checksum исходников, mapping и результаты проверки сертификата. Debug-подпись не является постоянной идентичностью приложения. Android обновляет установленное приложение только совместимой подписью. [Android: подпись приложения](https://developer.android.com/studio/publish/app-signing).

## Сборка

Нужны JDK 17, Android SDK platform 36 и Build Tools 35.0.0+, рабочий `local.properties`/SDK path. В текущем рабочем пространстве JDK расположен в `.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home`.

```sh
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" ./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease
```

Эта команда не публикует приложение. Без signing environment release остаётся unsigned. Не использовать `clean` ради каждой сборки: это удалит предыдущие локальные артефакты. Перед финальной передачей сопоставить содержимое `outputs` с текущими build tasks и evidence.

Автоматические проверки выполняются отдельно, без одновременного запуска задач, записывающих одни и те же Gradle test outputs:

```sh
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" ./gradlew :app:testDebugUnitTest :core:data:testDebugUnitTest :core:ssh:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" ./gradlew :app:connectedDebugAndroidTest
```

Live suites требуют отдельной disposable fixture и приватной конфигурации из testing README; отсутствие fixture означает пропуск opt-in tests, а не доказанную интеграцию. В отчёте должны быть отдельные числа прошедших/упавших/пропущенных проверок. Не использовать production для создания тестовых записей или установки серверов.

## Подпись без секретов в исходниках

`app/build.gradle.kts` читает только переменные окружения:

| Переменная | Значение |
| --- | --- |
| `MEETSPACE_KEYSTORE` | Абсолютный путь к существующему keystore вне checkout |
| `MEETSPACE_KEY_ALIAS` | Alias выбранного ключа |
| `MEETSPACE_STORE_PASSWORD` | Пароль keystore из secret store/защищённого локального ввода |
| `MEETSPACE_KEY_PASSWORD` | Пароль private key из secret store/защищённого локального ввода |

Нужно задать либо все четыре переменные, либо ни одной. Частичная конфигурация останавливает Gradle с инструкцией, чтобы ошибка не превратилась в неожиданную unsigned сборку. Секреты не передаются `-Ppassword=...` в аргументах процесса и не записываются в Gradle/property files; отключить shell tracing и вывод environment в CI. Keystore хранится и резервируется владельцем отдельно. `.gitignore` исключает `*.jks`, `*.keystore`, `keystore.properties`, `signing.properties`; это дополнительная защита, а не замена обращения с секретами.

После настройки окружения владельцем:

```sh
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease
```

При первой публикации владелец должен выбрать постоянную signing identity и схему Play App Signing/upload key. Для существующего приложения нужен совместимый ключ, а не новый случайный. Если выбран Google Play App Signing, upload key и app signing key выполняют разные функции. Пароли и ключи не нужно передавать в чат. [Официальная инструкция по ключам и Play App Signing](https://developer.android.com/studio/publish/app-signing).

## Конфигурация сервера и Firebase

Backend выбирается пользователем по HTTPS. Адрес production и тестовые credentials не вшиваются в APK. Установщик использует закреплённый backend commit и проверяемый bundle; signing key приложения не имеет отношения к SSH host key или подписи installer bundle.

По текущему уточнению пользователя Firebase и push-шлюз ещё не настроены. `app/google-services.json` отсутствует, сборка штатно работает без него, foreground/in-app уведомления доступны. Наличие Firebase зависимости само по себе не включает фоновые push. Подключение выполняется по [07_FCM_SETUP.md](07_FCM_SETUP.md), после чего повторяются manifest/security/live tests и обновляется документ потоков данных. Файл конфигурации и ключи шлюза не входят в артефакты этого этапа.

Media3 ExoPlayer/UI закреплены на версии 1.11.1 в Gradle. Источник сведений о библиотеке: [AndroidX Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3). Native WebRTC и AndroidX библиотеки требуют отдельной проверки compatibility, независимо от версии проигрывателя.

## Проверка пакетов

- APK: размер, SHA-256, `applicationId`/versionCode/min/target SDK, debug flag, actual permissions/components, installer assets и лицензии.
- Подписанный APK: `apksigner verify --verbose --print-certs`; fingerprint должен совпадать с выбранной владельцем идентичностью. У unsigned APK отсутствие подписи ожидаемо, установку не обещать.
- AAB: структура и manifest, `bundletool validate`; после реальной подписи проверить подпись и сгенерировать/install split APKs для нужного устройства. Успешный `bundleRelease` не доказывает корректность Play-delivered splits.
- Исключить fixture CA/private keys, тестовые пароли/токены, private properties, signing keys, ненужные debug assets из финального APK/AAB. Проверить manifest после merge, не только исходный XML.
- Сохранить release mapping и список лицензий. WebRTC license assets находятся в `app/src/main/assets/licenses`.

Для native-библиотек проверять ZIP alignment APK (`zipalign -c -P 16 4`), ELF `PT_LOAD` (не меньше `2**14` для 64-bit ABI), границу `GNU_RELRO`, а также реальный запуск на 16 KiB системе. Одна статическая проверка не доказывает runtime-совместимость. Android Developers отдельно описывает RELRO и 16 KiB проверку; перед публикацией сверить текущие требования магазина. [Поддержка 16 KiB](https://developer.android.com/guide/practices/page-sizes).

Проверка опубликованных AAR выявила узкое обновление: DataStore 1.1.7 → **1.2.1** исправляет границу GNU_RELRO у `libdatastore_shared_counter.so` для arm64-v8a и x86_64. Новая стабильная graphics-path 1.1.0 сохраняет обе невыравненные границы, а WebRTC 150.7871.01 сохраняет такое замечание для x86_64 и добавляет его на arm64 относительно используемой 144.7559.15; поэтому эти зависимости ради 16 KiB не обновлялись. SHA-256 скачанных AAR и фактические ELF-заголовки сохранены в [native-candidates.json](evidence/08/native-candidates.json). Результат проверки на 16 KiB системе фиксируется отдельно в итоговом отчёте. Источники версий: [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore), [graphics-path](https://developer.android.com/jetpack/androidx/releases/graphics#graphics_path_version_11), [WebRTC 150.7871.01](https://github.com/webrtc-sdk/android/releases/tag/v150.7871.01).

## Checklist владельца выпуска

- [x] Debug, minified APK, AAB и mapping собраны из одной проверенной версии; hashes и результаты приложены к отчёту.
- [ ] Signing identity выбрана/сохранена; release подписан владельцем; сертификат проверен; upgrade с предыдущей версией проверен без удаления пользовательских данных.
- [x] Выполненные JVM/UI/live suites пройдены; skips и непроверенные сценарии перечислены отдельно.
- [ ] Записи: авторизация/отзыв доступа, список/пагинация, Range/seek, истёкшая ссылка, повторный вход, ротация, отмена SAF, checksum, большой файл и нехватка места проверены для целевых устройств/провайдеров.
- [x] Исторические готовые материалы остаются доступны, даже если генерация транскриптов/итогов сейчас выключена на сервере.
- [ ] Обе onboarding ветки: существующий HTTPS сервер; SSH host trust/preflight и честная инструкция при отсутствии DNS/доступа. Невыполненное развёртывание не отмечено как успешное.
- [ ] Планшеты 7–13″/телефон: portrait/landscape/multiwindow, большие шрифты, light/dark, экранная и аппаратная клавиатура, TalkBack. Автоматические semantics checks не подменяют ручной TalkBack review.
- [ ] Native ABI, 16 KiB runtime и APK, полученные из AAB, проверены. Найденные compatibility blockers устранены до распространения.
- [ ] Background/foreground, потеря сети, отзыв разрешений, завершение screen share, logout/account switch и cold start проверены на физических устройствах; OEM/battery сценарии не объявлены проверенными по эмулятору.
- [x] Firebase включён только при наличии проекта/шлюза и пройденных live push checks; текущие UI и release notes явно говорят о выключенной фоновой доставке.
- [ ] Владелец заполнил реальные сведения о хранении/удалении данных/поддержке и публичную политику по [08_PRIVACY.md](08_PRIVACY.md); магазин отражает именно поставляемую конфигурацию.
- [ ] Доступность production backend и текущая серверная совместимость проверены перед выпуском. Production не изменялся в ходе disposable smoke tests.

После выполнения этого этапа дальнейшая публикация или развёртывание выполняется отдельным решением владельца.
