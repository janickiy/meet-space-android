# Проверки этапа 06

## Автоматические проверки приложения

JDK 17, SDK 36, Build Tools 35.0.0. Для окружения этой работы:

```sh
export JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home"
./gradlew testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.meetspace.app.FoundationUiTest,com.meetspace.app.SshWizardUiTest,com.meetspace.app.InstallerUiTest,com.meetspace.app.MeetingsUiTest,com.meetspace.app.CallUiTest
```

Новый `MeetSpaceTestRunner` использует тестовый Application. Без приватного `live-call.properties` он возвращает обычный production CallEngine. Тестовые классы, fixture CA и LiveCallBackend не входят в основной APK. LiveCallBackend использует настоящие HTTPS/WSS маршруты с проверкой имени хоста и доверием исключительно временному CA. Production DefaultFoundationRepository отдельно проверяется JVM-интеграцией с тем же сервером.

## Изолированный настоящий backend

`testing/stage06/fixture.py` создаёт новый private home (0700), чистый checkout фиксированного commit, PostgreSQL с настоящими миграциями, собственный Redis container, Gin REST/WSS, production media controller и Pion SFU. Pion TURN использует TURN REST HMAC credentials backend. Все новые клиенты принудительно используют relay. Никакие TLS-проверки не отключаются.

Нужны Go, PostgreSQL 14 tools, Docker, OpenSSL и свободные порты 15466/18466/16366/18966/50166/13466. Текущий launcher рассчитан на macOS с LAN-интерфейсом en0; адрес должен быть достижим из Android. HTTPS сертификат содержит 127.0.0.1, localhost и 10.0.2.2. Сервисы пользователя и production не используются.

```sh
python3 testing/stage06/fixture.py start --backend-source .audit/meet-space-stage04
python3 testing/stage06/run_peers.py --count 4
```

Последняя команда создаёт отдельную встречу и 1–4 дополнительных Pion endpoints. Первый возвращает полученные от Android Opus/VP8 RTP, сохраняя непрерывную последовательность/timestamps и передавая запросы ключевого кадра. Остальные читают настоящие RTP. Это детерминированный источник/приёмник теста, не production mock. Файлы `snapshot.json` и `peers-stats.json` содержат только счётчики. `live.properties` содержит временные credentials: не печатать, не передавать в аргументах runner/Gradle и не коммитить.

Для JVM-интеграций:

```sh
MEETSPACE_STAGE05_LIVE=1 MEETSPACE_STAGE05_FIXTURE="$PWD/.audit/stage06-fixture/live.properties" \
MEETSPACE_STAGE06_LIVE=1 MEETSPACE_STAGE06_FIXTURE="$PWD/.audit/stage06-fixture/live.properties" \
./gradlew :core:data:testDebugUnitTest
```

Для Android установить debug/test APK. Скопировать `live.properties` в приватный `files/live-call.properties`, заменив только origin на `https://10.0.2.2:18466`; CA — в `files/live-call-ca.pem`. Передавать содержимое через stdin в `adb shell run-as`, без секретов в командной строке. Не устанавливать CA в системное хранилище. Эмулятор должен иметь камеры: `-camera-back emulated -camera-front emulated`. `-no-audio` оставляет проверку реального Opus RTP с тишиной, субъективное качество звука этим не проверяется.

Запускать методы отдельно, чтобы подготовить соответствующие разрешения:

```sh
adb shell am instrument -w -e class 'com.meetspace.app.LiveCallTest#nativeCameraRelayReconnectAndCleanup' com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
adb shell am instrument -w -e class 'com.meetspace.app.LiveCallTest#screenConsentBackgroundAndStop' com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
adb shell am instrument -w -e class 'com.meetspace.app.LiveCallTest#lostNetworkReconnectsWithDevicesOff' com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
adb shell am instrument -w -e class 'com.meetspace.app.LiveCallTest#unreachableTurnFailsAndRetryRecovers' com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
adb shell pm revoke com.meetspace.app android.permission.CAMERA
adb shell am instrument -w -e class 'com.meetspace.app.LiveCallTest#deniedCameraAllowsListening' com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner
```

Первый тест включает эмулированные камеру/микрофон, проверяет выбранный relay, декодированные кадры и Opus, повороты portrait/landscape, пересоздание Activity, явный разрыв WSS, наблюдаемое RECONNECTING, новое подключение, выход и повторный вход. Второй нажимает настоящее системное разрешение MediaProjection и кнопку уведомления, проверяет rotation/background/recreation/cleanup. Третий временно отключает Wi-Fi/mobile data эмулятора и восстанавливает их в finally. Четвёртый подставляет недостижимый TURN только в тестовом transport и затем возвращает настоящий. Это проверка radio/network loss в эмуляторе, не физического handover между Wi-Fi и оператором.

`run_peers.py --count 1` создаёт новую комнату для проверки двух участников; после смены комнаты снова скопировать приватный properties. Для проверки пяти участников используется `--count 4`.

`run_peers.py --count 1 --screen-echo` публикует возвращаемый VP8 под настоящим source `video/screen`. Первый native тест дополнительно проверяет, что Android получает screen track и отображает его в отдельной области. Это проверка приёма screen source на реальном SFU; контент — эмулированная камера. Исходящая демонстрация Android проверяется отдельным тестом с настоящим MediaProjection. После изменения параметров peers повторно скопировать properties в приложение.

Для компактного UI: `adb shell wm size 1080x1920`, `adb shell wm density 480`, `adb shell settings put system font_scale 1.5`, затем только `CallUiTest`. Кнопка подключения на маленьком экране доступна прокруткой prejoin; тест использует `performScrollTo()`. После проверки восстановить прежние size/density/font_scale. Скриншот `stage06-call-layout.png` содержит статический UI fixture, а не реальный звонок.

## Очистка

После тестов удалить приватные `live-call.properties`, `live-call-ca.pem` из app files и тестовые скриншоты/cache, завершить приложение. Зафиксировать отсутствие MediaProjection и foreground service. Остановить все созданные endpoints и убедиться, что SFU показывает 0 rooms/peers/tracks/subscriptions. Затем:

```sh
python3 testing/stage06/fixture.py stop --remove
```

Stop проверяет marker и PID executable path, удаляет только принадлежащий fixture Redis container. Временные пароли, JWT/signing secrets, CA private keys, PostgreSQL data и бинарники удаляются вместе с private home. Диагностические результаты без секретов сохраняются в `docs/android/evidence/06`.

## Что требует реального устройства

Физические камеры и микрофоны, Bluetooth classic/BLE/USB, входящий телефонный звонок/аудиофокус, handover Wi-Fi↔LTE/5G, длительные групповые звонки/тепловая нагрузка и Android с 16KB страницами. ELF/ZIP alignment проверяется автоматически, но не заменяет запуск на таком устройстве. Системный звук демонстрации, PiP и работа камеры/микрофона в фоне не реализуются этим этапом.
