# Этап 06: нативные звонки — план до реализации

Запрос пользователя: WebRTC, Pion/SFU, видеозвонки и демонстрация экрана. Приложенный файл 08 относится к записям и релизу; эти задачи не входят в текущий этап. Ориентир интерфейса — первая версия `design/meetspace-android-20261010`.

## Проверенный контракт и gaps

- Публичный HEAD 11 октября 2026: `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`; исследован чистый `.audit/meet-space-stage04`. Рабочий backend пользователя не изменяется.
- WSS `/api/v1/conferences/{id}/ws`, subprotocol `go-recorder.v1`, envelope v1, обязательный свежий `conference.state`. Bearer доступен нативному клиенту в заголовке; секреты не проходят через UI.
- `media.join` → `media.joined` → клиентский offer с фактическими MID/publications → answer → `media.ready` → ack. Один offer за раз, ревизии объединяются. ICE буферизуется до SDP; Opus/VP8, max-bundle.
- Источники `microphone`, `camera`, `video/screen`, `audio/screen`. Экран поддерживается фактическим кодом; старый `docs/media-sfu.md` описывает более раннее состояние. Системный звук Android пока не включается в публикацию.
- G01: controller не передаёт `iceTransportPolicy`/`iceExpiresAt` из worker в joined. Используем `/api/v1/webrtc/config`, проверяем expiry, не ослабляем relay.
- G02: штатный Android SDK ещё не подключён. Выбран закреплённый `io.github.webrtc-sdk:android:144.7559.15`, поддерживаемая ветка M144 (upstream commit `30d5e63ae91da483e06577b5c35ee91cc5e5c3db`). Проверим AAR/JNI/16KB и релизную сборку.
- G03: stage05 fixture не имеет SFU/Redis. Для проверки требуется расширенный изолированный стенд; бизнес-данные production не изменяем.
- G04: физическая камера/микрофон, Bluetooth и переход Wi-Fi↔cellular требуют реального устройства. Эмулятор позволяет проверить протокол, VP8/RTP, захват экрана, lifecycle и сбои, но не качество физического оборудования.

## Реализация

1. Защищённый транспорт звонка в core:data, привязанный к текущему origin/account. Ограниченные очереди/размеры, TLS без обходов, закрытие сокетов при logout/switch.
2. core:media: native PeerConnection, последовательная сигнализация вне UI, prejoin, camera/mic, roster, policy, reconnect с новым endpoint, ICE/TURN, диагностика без SDP/секретов, deterministic cleanup.
3. Экран звонка Compose: адаптивная сетка, участники, состояние соединения, prejoin permissions, аудиомаршрут, переключение камеры, leave. Сохранение звонка при пересоздании Activity.
4. MediaProjection только после системного consent; foreground service и уведомление с остановкой. В фоне камера/микрофон приостанавливаются, экран продолжает работу только в разрешённом foreground service. Повторный захват требует нового consent.
5. Контрактные/JVM/UI тесты и реальные native↔Pion звонки на изолированном стенде, несколько peers, relay и lifecycle. APK и отчёт с фактическими результатами и ограничениями.

## Первичные источники

- [SDK и версия](https://github.com/webrtc-sdk/android/blob/main/CHANGES.md)
- [MediaProjection и одноразовый consent](https://developer.android.com/media/grow/media-projection)
- [Типы foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Аудиомаршруты и Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)

План проверок не является заявлением об их прохождении. Итоговые результаты фиксируются отдельно.
