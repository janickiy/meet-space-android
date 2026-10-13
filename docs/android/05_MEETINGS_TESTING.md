# Проверки этапа 05

Нужны JDK 17, SDK 36 и ARM64 эмулятор. Из корня Android:

```sh
./gradlew testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.meetspace.app.FoundationUiTest,com.meetspace.app.SshWizardUiTest,com.meetspace.app.InstallerUiTest,com.meetspace.app.MeetingsUiTest
```

Обычный JVM suite пропускает `LiveMeetingsRepositoryTest`, когда нет opt-in и приватной fixture. Для реального API следовать [testing/stage05/README.md](../../testing/stage05/README.md): создать новую `.audit/stage05-fixture`, запустить реальные Gin/PostgreSQL с проверяемым TLS, выполнить smoke и live repository test. Один отдельный test client доверяет только сгенерированному тестовому CA; hostname verification сохраняется. После всех Gradle invocation остановить и удалить fixture. Нельзя параллельно запускать два одинаковых Gradle test task, поскольку они перезаписывают один каталог результатов.

```sh
python3 testing/stage05/fixture.py start
python3 testing/stage05/backend_contract_check.py
MEETSPACE_STAGE05_LIVE=1 MEETSPACE_STAGE05_FIXTURE="$PWD/.audit/stage05-fixture/live.properties" ./gradlew :core:data:testDebugUnitTest
python3 testing/stage05/fixture.py stop --remove
```

Результаты credential-free; не копировать `live.properties`, TLS keys или settings/JWT в docs/source. Staging tests изменяют только одноразовую БД. Никаких production mocks в APK или бизнес handlers fixture нет. Upstream media, Redis и SFU в эту проверку не входят.

## Телефон и широкий экран

Для телефона устанавливались wm size 1080×2400, density 480 и font_scale 1.5. Повторялись 14 тестов MeetingsUiTest до добавления широкого сценария. Новый `tabletShowsListAndOwnerDetailsSideBySide` предназначен для широкого окна и автоматически пропускается на узком; весь класс теперь можно запускать на обоих размерах. Сценарий compact проверяет single-pane и удаление transient draft при restoration; отдельно сохранён его screenshot. После проверки выполнить `wm size reset`, `wm density reset`, `settings put system font_scale 1.0` и вернуть естественную ориентацию.

Gradle connected tests могут удалить APK после завершения. Для ручного instrumentation или запуска результата установить заново debug и test APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.meetspace.app.MeetingsUiTest#tabletShowsListAndOwnerDetailsSideBySide com.meetspace.app.test/androidx.test.runner.AndroidJUnitRunner
```

## Реальные Android страницы и auth

`LiveAuthTest` принимает временный `files/live-auth.properties` только в debug app sandbox: поля `server`, `email`, `password`. Значения не передаются как runner/Gradle arguments и не входят в source. Использовать тестовый аккаунт и отдельный эмулятор. Первый метод `connectLoginAndRotate` проверяет главный экран, активные встречи, календарь и кабинет, portrait/landscape, пересоздание Activity и тему. Он не создаёт встречи и не сохраняет имя. Затем принудительно остановить процесс и отдельно выполнить `restoreAfterProcessRestartAndLogout`. Второй метод проверяет Keystore/cookie восстановление и remote logout, удаляет private input.

Тест сохраняет четыре реальные screenshots в app cache для локальной визуальной проверки. Они могут содержать данные аккаунта: в публичные evidence экспортированы только контролируемые Compose fixtures. После проверки удалить screenshots/cache fixtures, удалить временные credentials и убедиться, что session отсутствует. Изменения встреч/профиля тестируются только на disposable staging.

Фактический прогон: [05_VERIFICATION.json](05_VERIFICATION.json), [05_MEETINGS_REPORT.md](05_MEETINGS_REPORT.md).
