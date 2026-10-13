# Аккаунты и настройки — 0.2.0-beta

Дата проверки: 13 октября 2026. `versionCode = 17`.

## Реализовано

- Раздел «Аккаунты» в настройках: карточки с именем, email, сервером и отметкой текущего аккаунта. Переключение доступно из карточки и меню планшета.
- Встроенная форма добавления с вкладками «Войти» и «Создать аккаунт», отдельной проверкой сервера, подтверждением пароля регистрации и двухколоночной компоновкой на широком экране.
- Добавление аккаунтов одного или разных HTTPS-серверов. Cookie хранятся раздельно в существующем защищённом хранилище по паре origin/userId. Пароли не сохраняются.
- Неудачное добавление не меняет текущую сессию. Ошибка сохранения профиля откатывает запись нового секрета. Повторный вход в ту же учётную запись не создаёт дубликат.
- Восстановление нескольких аккаунтов одного сервера после перезапуска. При смене личности существующие механизмы очищают данные экранов, уведомления и соединения предыдущего пользователя.
- Удаление текущего аккаунта с подтверждением паролем. Ошибки 422/409 сохраняют аккаунт. После подтверждённого удаления локальная сессия удаляется и восстанавливается следующий сохранённый аккаунт, если он есть.
- При 401 обновляется сессия, но DELETE автоматически не повторяется: требуется новое подтверждение пользователя. Подтверждённое сервером удаление не отменяется в памяти из-за ошибки локального хранилища.
- Смена и удаление аккаунта из настроек заблокированы во время звонка/встречи.
- Параметры уведомлений, включая звук, отображаются переключателями. События и email сохраняются через существующий API. Звук системного канала настраивается в Android; переключатель открывает эти настройки и отражает их состояние после возврата.

## Контракт сервера

Сверены `docs/account-management.md`, `internal/app/auth/accounts.go` и маршруты репозитория [meet-space](https://github.com/janickiy/meet-space).

Удаление: `DELETE /api/v1/auth/me`, JSON `{"password":"…"}`. Общая история других участников сохраняется; сервер может блокировать удаление при владении группами/незавершёнными встречами или участии в звонке.

`accounts/list` и `accounts/switch` предназначены для набора HttpOnly cookie одного браузера. Android сохраняет отдельные сессии и переключается через существующий `/auth/refresh` с cookie выбранного аккаунта; cookie остальных аккаунтов в запрос не попадают. Это также позволяет подключать разные серверы.

## Проверки

Команда сборки из корня проекта:

```sh
JAVA_HOME="$PWD/.audit/tools/jdk/jdk-17.0.20.1+1/Contents/Home" ./gradlew \
  :app:testDebugUnitTest :core:data:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

- 320 JVM-тестов прошли, 12 интеграционных тестов пропущены без внешнего стенда. В том числе 43 теста FoundationRepository и 18 тестов FoundationViewModel.
- Lint: 0 ошибок, 55 предупреждений в проекте; результат сборки — `BUILD SUCCESSFUL`.
- На `MeetSpace_Tablet_API_36` прошли 19 поведенческих UI-тестов. Три проверки реальных медиоустройств и необязательный снимок пропущены в общем запуске; снимки затем запущены отдельно.
- Восемь отдельных запусков снимков прошли: аккаунты в светлой/тёмной теме, форма добавления и уведомления для 390×844 и 1194×834 dp. Снимки просмотрены. Размер эмулятора восстановлен.
- Проверены подпись APK и совпадение сертификата с предыдущим `MeetSpace-0.1.0-beta.apk`. Итоговый APK установлен на эмулятор поверх предыдущего без удаления данных.

Отчёты, контрольная сумма и снимки: [evidence/accounts-0.2.0](evidence/accounts-0.2.0/artifact.json).

## Артефакт и ограничения

APK: `dist/MeetSpace-0.2.0-beta.apk`, отладочная сборка, 78 176 769 байт. SHA-256 хранится рядом в `MeetSpace-0.2.0-beta.apk.sha256`.

Новые сценарии серверного удаления проверены с HTTPS MockWebServer по контракту backend. Реальные аккаунты на production не удалялись. Сквозной сценарий с регистрацией двух новых пользователей и удалением на живом сервере, а также физическое Android-устройство в этой проверке не использовались. Firebase/push остаётся зависимым от внешней настройки. Параметры камеры, микрофона, размера текста и темы остаются настройками устройства; серверные данные и настройки уведомлений относятся к выбранному аккаунту.

## Изменённые исходники

- `app/build.gradle.kts`
- `core/model/src/main/kotlin/com/meetspace/core/model/FoundationModels.kt`
- `core/data/src/main/kotlin/com/meetspace/core/data/FoundationRepository.kt`
- `core/data/src/main/kotlin/com/meetspace/core/data/DefaultFoundationRepository.kt`
- `core/data/src/main/kotlin/com/meetspace/core/data/MeetSpaceApi.kt`
- `app/src/main/java/com/meetspace/app/FoundationViewModel.kt`
- `app/src/main/java/com/meetspace/app/MainActivity.kt`
- `app/src/main/java/com/meetspace/app/ui/FoundationUi.kt`
- `app/src/main/java/com/meetspace/app/ui/ProfileWorkspace.kt`
- `app/src/main/java/com/meetspace/app/ui/AccountMediaPanels.kt`
- `core/data/src/test/kotlin/com/meetspace/core/data/FoundationRepositoryTest.kt`
- `app/src/test/java/com/meetspace/app/FoundationViewModelTest.kt`
- `app/src/androidTest/java/com/meetspace/app/ProfileDesignTest.kt`
- `app/src/androidTest/java/com/meetspace/app/SettingsUiTest.kt`
- `app/src/androidTest/java/com/meetspace/app/AccountMediaPanelsTest.kt`
