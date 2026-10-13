# Этап 06 — WebRTC, Pion/SFU и демонстрация экрана

Завершён 11.10.2026. Android **0.6.0**, versionCode **5**. Выполнен прямой запрос пользователя о видеозвонках. Приложенный файл 08 относится к записям и подготовке релиза; этот продуктовый этап не запускался. План перед кодом: [06_WEBRTC_PLAN.md](06_WEBRTC_PLAN.md). Машиночитаемые результаты: [06_VERIFICATION.json](06_VERIFICATION.json).

## Реализовано

- Из деталей активной встречи открывается prejoin. Камера и микрофон включаются по выбору пользователя после runtime permission; отказ позволяет войти для прослушивания. Перед медиавходом повторно проверяются активность встречи и допуск через существующий REST API.
- Нативный `core:media` с закреплённым `io.github.webrtc-sdk:android:144.7559.15`: Opus/VP8, Unified Plan, SDP/ICE, TURN, публикация камеры и микрофона, получение потоков участников. Сигнализация и изменения PeerConnection последовательно выполняются вне UI thread.
- Настоящий протокол backend: свежий WSS ticket и `conference.state`, `media.join`, offer с фактическими MID/publications, answer, `media.ready` и ack. Одновременно выполняется одна SDP-транзакция; объединяются запросы renegotiation, учитываются ревизии track catalog и версии policy. ICE до remote SDP буферизуется с ограничением размера.
- Адаптивный тёмный экран по первой версии дизайна: сетка видео, отдельная область демонстрации, список участников сбоку на широком экране и диалог на компактном, переключение камеры, выключение устройств, выбор аудиоустройства, подтверждение выхода и состояния подключения/ошибки.
- Аудиофокус, системные коммуникационные устройства, динамик/телефон/гарнитура/Bluetooth. API 31+ использует `setCommunicationDevice`; старые версии — speaker/SCO. При потере аудиофокуса передача камеры/микрофона приостанавливается.
- MediaProjection с новым системным согласием на каждый запуск, отдельным `video/screen`, foreground service типа `mediaProjection` и уведомлением с остановкой. Собственный экран представлен пояснением без бесконечного зеркального предпросмотра. Экран другого участника выводится отдельно с сохранением пропорций.
- Поворот и пересоздание Activity сохраняют CallEngine. В фоне камера и микрофон выключаются; пользователь включает их снова после возврата. Разрешённая демонстрация продолжает работу до остановки пользователем/Android, выхода или потери связи.
- Восстановление после разрыва WSS/ICE создаёт свежие ticket и media endpoint; число попыток ограничено. После reconnect устройства остаются выключенными, повторная демонстрация требует нового согласия. Учитывается краткое сохранение старого endpoint в полной комнате.
- Выход, отказ, смена аккаунта/сервера и завершение встречи закрывают сокет, PeerConnection, захват, native tracks, render sinks, аудиофокус и foreground service. Ошибка подтверждения REST leave не удерживает медиаресурсы.

## Совместимость и защита

Реальная интеграция проверена на публичном backend commit **`6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`** в отдельном стенде. Исходный рабочий checkout пользователя и production не изменялись. Стенд использует production REST/WSS handlers, PostgreSQL migrations, Redis, media controller/worker и Pion SFU; тестовые peers действительно принимают/передают RTP.

В этом commit controller не передаёт `iceTransportPolicy` и `iceExpiresAt` из worker в `media.joined`. Android получает их через `/api/v1/webrtc/config`, проверяет TURN URL/expiry и сохраняет требование relay. При приближении expiry выполняется полный reconnect; плавное обновление ICE без перерыва пока не реализовано. Камера/микрофон после этого включаются вручную.

WSS привязан к выбранным HTTPS origin и account через одноразовый ticket. Основной transport использует обычную проверку сертификата/имени хоста, запрещает redirects, не передаёт cookie/Bearer в WebSocket и закрывает звонок при очистке auth context. Очереди, SDP, ICE и входящие сообщения ограничены. Секреты, SDP и ICE не выводятся в UI или логи; диагностика содержит числовые счётчики и безопасные коды.

Fixture CA и временные credentials существовали только в приватных тестовых файлах. Тестовый Android Application использовал настоящий HTTPS/WSS с проверкой hostname и доверием только CA стенда; production data transport отдельно прошёл JVM-интеграцию с тем же сервером. Системное trust store не менялось. Тестовые классы, private files и credentials отсутствуют в основном APK.

## Фактические результаты

| Проверка | Результат |
|---|---|
| JVM: app 44, core:ssh 57, core:data 58, core:media 14 | **173 passed**, 0 failures/errors/skips; оба opt-in live REST/WSS теста включены |
| Общий UI suite, Android 16 ARM64, 2560×1600 / 320 dpi | **40 passed**; изменённые CallUiTest повторно прошли 5/5 |
| Call UI, 1080×1920 / 480 dpi, шрифт 150% | **5 passed**; prejoin подключается после доступной прокрутки |
| Android + один Pion peer | **2 участника**, фактические Opus/VP8, декодированные кадры, выбранный TURN relay; WSS reconnect, leave/rejoin |
| Android + четыре Pion peers | **5 участников**, Opus/VP8 через SFU, relay на Android; поворот portrait/landscape и Activity recreation |
| Исходящий экран Android | Настоящий системный consent и MediaProjection; четыре peers получили 504–505 screen RTP packets в финальном прогоне; rotation, background и остановка из уведомления прошли |
| Входящий screen source | Pion публикует настоящий `video/screen` с эмулированным содержимым; Android декодирует и отображает отдельную область, сохраняет работу после reconnect |
| Потеря сети | Wi-Fi/mobile data эмулятора отключены/включены; обнаружен RECONNECTING, восстановлены соединение и relay, устройства остались выключенными |
| Недоступный TURN | Настоящая неудача native ICE, понятная ошибка и освобождение ресурсов; после возврата достижимого TURN повторный вход успешен |
| Отказ в CAMERA permission | Настоящий системный отказ, затем успешный вход без камеры |
| Сборки | Debug, AndroidTest и minified unsigned Release успешны |
| Android Lint | **0 ошибок**, 23 предупреждения об обновлениях зависимостей/инструментов |
| Подпись и выравнивание | Debug v2 signature проверена; debug/release ZIP alignment 16 KiB; arm64/x86_64 native ELF LOAD alignment 16 KiB |
| Состав APK | Пять публичных installer assets совпадают с исходными; WebRTC licenses включены; fixture secrets/private files отсутствуют |
| Очистка | SFU: **0 rooms, 0 peers, 0 tracks, 0 subscriptions**; MediaProjection null, app services отсутствуют |

Контрольные показатели пяти участников: Android получил **61 974 bytes**, включая **4 830 audio bytes** и **24 decoded frames**. После WSS reconnect и повторного включения только камеры — **49 169 bytes / 27 decoded frames**, relay=true. Аудио после этого reconnect намеренно оставалось выключенным. Это не измерение производительности или качества звука.

Во время интеграции устранены повторные offers из неизменённого `media.policy`, использование освобождённых SDK wrappers transceiver и преждевременный отказ reconnect при занятом старым endpoint. В тестовом стенде исправлены TURN REST username format и непрерывность RTP echo после смены источника. На компактном UI два первых теста нажимали кнопку за пределами viewport; они исправлены на явную прокрутку, повторный прогон успешен. Непройденных итоговых тестов нет.

## Доказательства и воспроизведение

- [Инструкция запуска](06_WEBRTC_TESTING.md), [воспроизводимый стенд](../../testing/stage06/README.md).
- [Финальные rotation/media тесты](evidence/06/rotation-and-media-instrumentation.txt), [два участника](evidence/06/two-peer-instrumentation.txt), [счётчики пяти участников](evidence/06/stage06-native-evidence.txt), [приёмники экрана](evidence/06/five-peer-pion-receivers.json).
- [Потеря сети](evidence/06/network-instrumentation.txt), [ошибка ICE](evidence/06/ice-failure-instrumentation.txt), [отказ в разрешении](evidence/06/denied-instrumentation.txt), [приём экрана](evidence/06/remote-screen-instrumentation.txt).
- Скриншоты настоящего стенда: [звонок](evidence/06/stage06-live-call.png), [демонстрация Android](evidence/06/stage06-live-screen.png), [входящий экран](evidence/06/remote-screen.png). [Компактная раскладка 150%](evidence/06/phone-large-font-layout.png) — отдельный статический UI fixture.
- [Проверка APK](evidence/06/apk-verification.json), [SDK/ELF](evidence/06/sdk-verification.json), [SFU после выхода](evidence/06/sfu-after-leave.json), [очистка окружения](evidence/06/cleanup.json).

После сохранения доказательств удалены временные аккаунты, PostgreSQL data, JWT/TURN secrets, CA private keys, собственный Redis container и тестовые Android files. Повторный запуск/остановка fixture launcher проверены с чистого состояния. Все шесть портов стенда освобождены, существующие Docker-сервисы пользователя продолжают работу. Размер/плотность/шрифт и сеть эмулятора восстановлены.

## Артефакты

- Устанавливаемый [debug APK](../../app/build/outputs/apk/debug/app-debug.apk): 71 382 677 bytes, SHA-256 `ad189e3699d92f1fb1f761be1ee84f34e25c18c445b7bf7477d0b9f791378b51`.
- [Minified release APK без публикационной подписи](../../app/build/outputs/apk/release/app-release-unsigned.apk): 50 471 939 bytes, SHA-256 `7d7e43298b82f253c00db9ab6adcb105447fb036855b4de57c106d6c95fdfa7c`.

## Изменённые файлы

| Группа | Файлы |
|---|---|
| Сборка | `settings.gradle.kts`, `app/build.gradle.kts`, `core/data/build.gradle.kts`, новый `core/media/build.gradle.kts` и `consumer-rules.pro` |
| Data | Новые `CallRepository.kt`, `ScopedCallSignaling.kt`; изменения `MeetSpaceApi.kt`, `DefaultFoundationRepository.kt` |
| Media | Новые `CallEngine.kt`, `CallProtocol.kt`, `CallState.kt`, `CallAudio.kt`, `SdpOperations.kt` |
| App/UI | Новый `ui/CallUi.kt`, `ScreenShareService.kt`; `MainActivity.kt`, `MeetSpaceApplication.kt`, `ui/MeetingsUi.kt`, `AndroidManifest.xml` |
| Лицензии | `app/src/main/assets/licenses/WEBRTC.md`, `webrtc-sdk-LICENSE.txt` |
| Тесты | Новый `CallProtocolTest.kt`, `CallUiTest.kt`, `LiveCallTest.kt`, `LiveCallBackend.kt`; дополнения `MeetingsRepositoryTest.kt`, `LiveMeetingsRepositoryTest.kt` |
| Воспроизведение/docs | `testing/stage06/{fixture.py,main.go,peers.go,run_peers.py,README.md}`, корневой README, документы 06 и `evidence/06` |

## Ограничения и дальнейшая работа

Физические камеры/микрофон, Bluetooth classic/BLE/USB, субъективное качество звука, телефонные прерывания и переход Wi-Fi↔LTE/5G требуют реальных устройств. В стенде использованы эмулированные камеры и реальный Opus с тишиной. Пять peers — один Android и четыре Pion клиента, не пять физических телефонов. Числовые проверки и нулевые SFU counters подтверждают cleanup проверенных сценариев, но не заменяют длительный native memory/thermal soak.

Не запускались Android 8–15, физический Android с 16 KiB страницами, установленная minified release-сборка, длительные звонки, фактический системный multiwindow и звонки на production-сервере. ELF/ZIP alignment проверен статически. Android audio routing реализован, аппаратная матрица не проверена.

Системный звук демонстрации, PiP, фоновые камера/микрофон, чат и записи не входят в реализованный функционал. Боковая панель содержит участников. Публикационная подпись и AAB относятся к отдельному релизному этапу.

При следующем запросе сначала сверить актуальный backend/API и выполнить аппаратный smoke: два устройства, камера/микрофон/гарнитуры, screen sharing, network handover, долгий звонок и освобождение ресурсов. Для сообщений и записей переиспользовать текущие границы origin/account и реальный серверный контракт. Следующий этап автоматически не запускался.
