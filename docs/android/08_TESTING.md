# Этап 08 — проверки записей, настроек и пакетов

Дата: 2026-10-11. Backend `6ecd8b648a2aa94fc9bcc844d57784423e2c6f69`, Android 0.8.0 (7). Воспроизведение стенда и команд: [testing/stage08/README.md](../../testing/stage08/README.md). Production не изменялся.

## JVM и реальный backend

| Проверка | Всего | Успешно | Ошибки | Пропущено |
| --- | ---: | ---: | ---: | ---: |
| app | 88 | 88 | 0 | 0 |
| core/data | 107 | 102 | 0 | 5 |
| core/ssh | 57 | 57 | 0 | 0 |
| core/media | 14 | 14 | 0 | 0 |
| Итого | **266** | **261** | **0** | **5** |

Пять пропусков — прежние opt-in live тесты этапов 05/06 (2) и 07 (3); соответствующие стенды сейчас не запускались. Все четыре текущих `LiveRecordingsBackendTest` выполнены успешно. Из 261 успешного теста 13 относятся к новому RecordingsViewModel, 20 — к repository записей. [JUnit XML](evidence/08/jvm/), [машинная сводка](evidence/08/jvm-summary.json).

Настоящий изолированный Gin/PostgreSQL/Redis/MinIO стенд использует private CA со стандартной hostname verification. Contract probe выполнил **758 assertions**: вход, пагинация встреч/записей, member/outsider/kicked/cross-meeting права, historical materials при выключенных providers, 205 сегментов, summary, cache headers, MinIO, полный SHA-256, HTTP 206 Range, неверная/неподписанная/истёкшая ссылка и HTTP 410 для старых маршрутов. Число включает проверки HTTP, подготовки данных и отдельных сегментов; это не 758 самостоятельных пользовательских сценариев. [Contract evidence](evidence/08/backend-contract.json).

FFmpeg создал десятисекундные H.264/AAC файлы из цветовых полос и синусоиды, без захвата камеры/микрофона. Готовность записи сохранялась настоящими методами repository. 205 исторических сегментов и summary явно подготовлены с provider `fixture_seed`, внешние STT/AI остались noop. Тестируется потребление готовой записи, а не полный серверный процесс её создания.

## Android

На существующем ARM64 эмуляторе API 36 с памятью 4 КиБ проверены **76 уникальных UI-тестов**, четыре сквозных сценария и один native smoke. Все имеют успешный завершающий результат. UI разбивка: Foundation 10, SSH 6, installer 4, meetings 16, call 5, messaging 19, recordings 12, settings 4.

Полный финальный прогон дал 80/81: дополнительная проверка воспроизведения/уведомления остановилась в тестовом управлении Activity. Исправленный метод отдельно прошёл 1/1. Это не один прогон 81/81; [android-test-runs.json](evidence/08/android-test-runs.json) сохраняет результаты отдельных запусков.

Сквозные сценарии `LiveRecordingsTest`:

1. Реальный вход, переход к встрече и записи, декодированные видео-кадры, перемотка на 5 секунд, пауза и сохранение позиции после поворота. Закрытие освобождает доступ; повторное воспроизведение прерывается запросом открытия inbox. Проверяются доставка настоящего Activity Intent, открытие центра, закрытый поток и отсутствие повторного события после следующего поворота. Получение уведомления через WSS/OS PendingIntent проверялось в этапе 07 и здесь не повторялось.
2. Исторические материалы при отключённых providers, три страницы из 205 уникальных сегментов, summary, отсутствие debug artifact, реальная тема, диалог о данных и версия.
3. Настоящий системный CreateDocument, потоковый экспорт MP4, SHA-256 сохранённого документа и удаление только созданного тестом UUID-файла. Shell читает hash как наблюдатель теста; приложение пользуется SAF URI и не получает широкое storage-разрешение.
4. Welcome → проверка HTTPS → UI login/logout; затем SSH-форма → реальная ошибка TCP на закрытом локальном `127.0.0.1:1` → понятное сообщение → выход в Welcome. Пароль не попадает в снимки и diagnostic dumps. Установка сервера не запускалась.

Телефонный размер **1080×1920, density 480, font scale 1.5**: все четыре сценария прошли по итогам повторного запуска onboarding; окончательная версия видео/уведомления дополнительно прошла 1/1. Планшет **2560×1600, density 320**: широкая/узкая компоновка, изменение ширины до 360 dp, окно 600×280 dp с большим шрифтом, light/dark, поворот, кнопки минимум 48 dp и клавиатурный Enter. Это изменение доступной области Compose, а не фактически включённый Android split-screen. Ручной TalkBack и физическая клавиатура не проверялись.

Просмотрены реальные снимки проигрывателя, материалов, настроек с крупным шрифтом и release Welcome: [tablet](evidence/08/tablet/), [phone](evidence/08/phone/), [ARM64 16 КиБ](evidence/08/arm64-16k/).

## 16 КиБ и релиз

Отдельный официальный образ Google API 36 revision 7 ARM64: `arm64-v8a-ps16k-36_r07.zip`, SDK SHA1 `dda632745023567113d9954101709c6526a3c250` проверен перед распаковкой. Фактический `PAGE_SIZE=16384`; `bionic.linker.16kb.app_compat.enabled=false`, `pm.16kb.app_compat.disabled=true`.

На окончательном debug APK **5/5**: четыре live сценария и `NativeLibrariesSmokeTest`. Загрузились `androidx.graphics.path`, `datastore_shared_counter`, `jingle_peerconnection_so`; WebRTC factory создана и освобождена без peer/захвата. Проверка graphics-path касается загрузки JNI: API 36 использует системный PathIterator, нативные операции этой библиотеки здесь не доказаны. Реальный WebRTC звонок на 16 КиБ в этом этапе не выполнялся.

Minified release APK, полученный через bundletool из окончательного AAB с временной подписью, установлен на этот же отдельный эмулятор после удаления его тестовой debug-установки. Обычный холодный запуск прошёл, обе onboarding-кнопки видимы, процесс жив, записей crash для MeetSpace нет. Test APK и private CA отсутствовали. Это release launch smoke, а не release login/playback со стендом. Подпись поставляемых APK/AAB остаётся unsigned; временный ключ smoke удалён.

`assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, `bundleRelease`, JVM suites и `lintRelease` завершились успешно. Lint: **0 errors, 46 warnings** (8 ApplySharedPref, 3 AndroidGradlePluginVersion, 12 GradleDependency, 10 NewerVersionAvailable, 9 UseKtx, 4 UseTomlInstead). Синхронный commit зашифрованных credentials выполняется на IO для подтверждения сохранения; прочие замечания — текущие предложения по зависимостям/стилю, они не скрыты baseline.

Пакеты прошли bundletool validation, проверку APK ZIP alignment 16 КиБ, 64-bit ELF PT_LOAD, signatures, merged permissions, FCM-off, backup/cleartext-off, installer assets/licenses и отсутствие известных private credentials/CA/test classes. AAB запрашивает PAGE_ALIGNMENT_16K. DataStore 1.2.1 устранил оба прежних RELRO замечания. Остались формальные GNU_RELRO end findings: graphics-path arm64/x86_64 и WebRTC x86_64. ARM64 runtime выше прошёл без compatibility; x86_64 и все прочие операции не объявляются проверенными. [Фактические AAR](evidence/08/native-candidates.json), [пакеты](evidence/08/artifact-verification.json), [runtime с SHA пакетов](evidence/08/runtime-16k.json). [Официальная методика Android](https://developer.android.com/guide/practices/page-sizes).

## Исправленные промежуточные ошибки и непроверенное

Первоначальные ошибки тестов были исправлены и перепроверены: точное сравнение вместо substring в settings; keyboard injection без перехода Compose из touch mode; нажатие offscreen SSH exit без прокрутки; попытка вызвать protected onNewIntent; перенос ActivityScenario CLEAR_TASK flags и его фильтрация временного inbox Intent. Последний regression использует обычный SINGLE_TOP Intent без тестовых flags и настоящий поворот устройства. Приложение не менялось ради обхода assertions.

Финальное ревью также выявило и исправило два пользовательских дефекта: исчезавшее объяснение отказа в материалах и неоткрывавшийся inbox поверх записи. Добавлены assertions к существующим JVM/live методам, включая однократное потребление Intent.

Не проверялись: новый VPS с DNS/SSH, полный Pion/worker/STT/AI pipeline, внешний FCM, физические устройства/OEM, x86_64 runtime, Play delivery/обновление с постоянной подписью, ручной TalkBack, многогигабайтные файлы и реальная нехватка места у разных облачных DocumentsProvider. Отмена, ограничения потока, ошибочный размер/hash, logout и смена аккаунта покрыты автоматическими тестами. Уже выданная backend storage URL действует до 24 часов после отзыва членства; новый API-доступ запрещается сразу.
