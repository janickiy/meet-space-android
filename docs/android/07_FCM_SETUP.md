# Необязательные фоновые push Android

Состояние этапа 07: пользователь подтвердил, что Firebase-конфигурации и push-шлюза пока нет. Код интеграции подготовлен, реальная доставка FCM не проверялась. Обычные чаты, уведомления внутри приложения и глобальный WSS работают без Firebase.

## Сборка и согласие

- Приложение собирается без `app/google-services.json`: `BuildConfig.FCM_CONFIGURED=false`, собственный FCM service выключен. Файл конфигурации исключён из Git. Тестовые/поддельные Firebase-проекты не создаются.
- Для настроенной сборки нужен подлинный `google-services.json` Firebase Android application с package `com.meetspace.app`. Gradle применяет Google Services plugin 4.4.4 только при наличии этого файла. В Android APK не помещаются ключи серверного service account.
- Версии зафиксированы: `firebase-messaging:25.0.1`, `work-runtime-ktx:2.10.5`. Firebase Analytics не подключается; collection и FCM auto-init выключены manifest metadata.
- `PushController.enable()` вызывается после отдельного согласия пользователя и разрешения Android на уведомления. Регистрация допускается только при `GET /api/v1/integrations/capabilities` → `push=http`; `noop` и `mock` не считаются рабочей доставкой. После успешной регистрации явное включение сохраняет `push=true` в notification preferences, сохраняя остальные поля. Локальное отключение не выключает push остальных устройств.
- `reconcile()` вызывается после завершённого восстановления/входа. Сохранённое согласие относится только к паре HTTPS origin + account UUID. FCM-токен не записывается в прикладные preferences или логи; приватно сохраняются только scope, UUID регистрации, согласие и ограниченные UUID уже показанных уведомлений.
- Перед logout, сменой сервера или аккаунта необходимо вызвать `beforeIdentityChange()`. Он сразу запрещает локальную доставку, отменяет WorkManager, очищает уведомления и ограничивает отзыв серверной регистрации + удаление FCM-токена восемью секундами. При сбое возвращает безопасное предупреждение. Следующее включение не переиспользует токен, чей сброс не подтверждён. Приложение не подписывается на FCM topics.

## Контракт шлюза

Аудированный backend использует generic HTTP push provider. `internal/domain/integrations/integration.go` задаёт поля `idempotencyKey`, `token`, `title`, `body`, `url`. Сам адаптер не отправляет запросы в FCM: реальный операторский шлюз должен перевести их в FCM HTTP v1.

Android ожидает **data-only** FCM-сообщение, где `data.url` — исходный backend URL. Не передавайте FCM `notification` payload: он обычно показывается SDK в фоне до вызова приложения. Делегирование показа Google Play Services также выключено manifest metadata; незащищённый SDK fallback-service удалён из merged manifest. Наш service отбрасывает notification-style intent до dispatch, а `onMessageReceived` игнорирует raw title/body. Этот защитный dispatch hook привязан к закреплённому SDK; при обновлении SDK его поведение требует повторной проверки.

Разрешены абсолютные HTTPS-ссылки только текущего origin и точные пути `/conferences/<UUID>` либо `/conversations/<UUID>`. Текущий backend формирует конференционный путь; второй путь — поддержка возможного расширения шлюза для чатов, а не заявление о его наличии. Допускаются только известные query-поля `recording=<UUID>` и `tab=transcript|summary`. Никакие URL из push не открываются и не загружаются напрямую.

WorkManager хранит только нормализованный UUID ресурса и scope, затем проверяет текущую авторизацию обычным REST, права на конференцию/чат и серверный список уведомлений. Системное уведомление создаётся только по подходящему непрочитанному серверному уведомлению; текст общий, без имени встречи, сообщений, файлов и capability URL. Дубли ограничиваются последними 100 UUID. Состояние текущего аккаунта проверяется ещё раз перед показом. При отсутствии сети/прав уведомление не подменяется ложным успехом. Права проверяются для первых 30 текущих уведомлений; устаревший hint вне этой страницы может привести только к синхронизации при открытии приложения.

Worker ограничен по времени и трём повторным попыткам. Фоновые ограничения Android, выключенные уведомления и отсутствие Google Play Services могут задержать доставку. WebSocket используется только в период активного приложения.

## Проверки до включения на реальном проекте

1. Добавить реальный клиентский Firebase JSON; проверить пакет и ключи сборки.
2. Настроить generic gateway с преобразованием в data-only FCM HTTP v1 и отключённым логированием токенов/тела.
3. На физическом устройстве с Google Play Services проверить выдачу/отказ разрешения, явное включение, `push=http`, серверную регистрацию и token rotation.
4. Проверить foreground/background/process death, data-only доставку, повтор, чужой origin, удалённое членство, offline cleanup и смену аккаунта. Убедиться, что неправильно сформированный notification payload не показывает raw content.
5. Не отмечать FCM как проверенный до реальной доставки. В этом этапе внешние сообщения не отправлялись.

Первичные источники: [Firebase Android setup](https://firebase.google.com/docs/android/setup), [FCM receive messages](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [FirebaseMessaging API](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessaging), [закрепляемые версии Firebase](https://firebase.google.com/support/release-notes/android), [WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work), [FirebaseMessagingService dispatch source](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-messaging/src/main/java/com/google/firebase/messaging/FirebaseMessagingService.java).
