# Проверки этапа 04

## Локальные проверки

Из корня Android-проекта с JDK 17 и Android SDK 36:

```sh
python3 -m unittest discover -s installer/tests -v
./gradlew testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meetspace.app.InstallerUiTest,com.meetspace.app.SshWizardUiTest,com.meetspace.app.FoundationUiTest
```

Python проверяет подпись/checksum, ограничения JSON, пути/ссылки архива, права исходников для непривилегированных контейнеров, инъекции, DNS/порты, подтверждение, журнал, публикацию портов, Coturn capabilities и результат CLI для systemd. JVM проверяет контракт/подпись плана, bounded bootstrap, привязку ответа к job/commit/hash, полный набор checkpoints перед success, таймауты, сохранение ticket до apply, потерянный ответ, отдельную отмену, явное resume и обновление неотправленного истёкшего плана. Существующие SSH/auth проверки остаются включены.

Compose: планшет 2560×1600 / 320 dpi; дополнительно 1080×2400 / 480 dpi (360 dp) со шрифтом 150%. После прогона размер/density/font scale возвращены к исходным. Набор InstallerUiTest проверяет три согласия, различие detach/cancel, отсутствие success при неизвестном состоянии и переход к HTTPS после завершения.

## Реальная VM

Использована отдельная Lima/VZ `meetspace-stage04`: `installer/tests/ubuntu24-arm64.yaml`. Никаких host mounts, загрузки личных SSH-ключей или agent forwarding. Только эта созданная для этапа VM менялась и удалялась. Для повторения требуется новая одноразовая VM; команды не предназначены для действующего сервера.

1. Проверить bundle production через OpenSSL. В отдельном игнорируемом каталоге скопировать пять публичных файлов, изменить только `profile` на `integration-v1` и `acmeDirectory` на `https://localhost:14000/dir`; подписать отдельным одноразовым тестовым RSA-ключом. Не заменять production assets.
2. Запустить Pebble `ghcr.io/letsencrypt/pebble@sha256:ddf230642b1a584f519f32e347de1b05a6e4c1f6c35c1863b33effeab5f78199`. HTTP challenge port 80, TLS challenge port 443, проверка challenge включена. `stage04.test` разрешается в IPv4 VM и внутри Pebble. Установить TLS CA Pebble и его выдающий root в системное доверие **только VM**. Никаких `-k`, trust-all или пропуска hostname verification.
3. На пустой VM проверить `plan`: NXDOMAIN → `DNS_FAILED`; временный пустой tmpfs 1 MiB mode 0700 на install dir → `DISK_SPACE`; временный HTTP listener 80 → `PORT_OCCUPIED`. Удалять только созданные fixture listener/mount. Проверка незнакомого существующего каталога не должна его перезаписывать.
4. Получить действительный plan через `installer/tools/bootstrap.py`, проверить JSON, явно передать hash, UUID v4, Docker consent и ACME consent. Доставить bootstrap через уже проверенный SSH. `apply` запускает detached systemd worker.
5. Закрыть SSH-соединение наблюдателя принудительно, затем новым соединением запросить status. Проверить, что worker продолжает работу. Проверить cancel на границе команды и явное resume из сохранённых фаз.
6. Дождаться `completed` с десятью фазами; сохранить только public evidence/state/image IDs. Проверить health сервисов, HTTPS API/frontend versions, отказ неавторизованному WebSocket, TURN TLS/Allocate/auth/private-peer denial и отсутствие host ports у приватных сервисов.
7. Повторить исходный apply и resume completed job: тот же UUID, сохранённые image IDs/secrets/данные. Новый plan должен вернуть existingJobId/existingPlanHash. Повторить migrator: число ledger migrations остаётся 33. Восстановить начальный dump в отдельную тестовую БД и удалить только её.
8. Создать одноразового пользователя на тестовом backend и выполнить вход/REST/создание и закрытие тестовой встречи/WSS snapshot через upstream `/app/release-smoke --environment staging --exercise` с явно доверенным CA. HTTP-учётные данные передавать через защищённый env-файл, удалить его после запуска. Никогда не использовать пользовательский аккаунт или production.
9. Проверить systemd timer renewal и однократный запуск service. В этом прогоне сертификат ещё не требовал продления; принудительная ротация не проверялась.

Первый прогон устанавливал Docker на пустую VM и собирал образы без прежнего build cache. После исправлений прав исходников и Coturn запускался заново на пустых томах, сохраняя только Docker build cache. Негативные плановые сценарии повторялись; drop/cancel/resume имеют отдельные сохранённые результаты ранних прогонов. Серверный механизм выполнения этих сценариев сохранён. Это не публичный ACME staging/production VPS и не проверка внешнего firewall.

## Android → реальный SSH

`InstallerLiveTest` запускается только с `-e installerLive 1`. Debug-only `InstallerFixtureFactory` читает отдельно подписанный integration bundle из private app files. Нужны защищённые `files/installer-stage04/{runner.py,release.json,release.sig,trust-root.pem,docker.asc,access.properties}`. Properties содержат endpoint одноразовой VM, отдельного SSH-пользователя, временный пароль, fingerprint из независимой консоли VM и ожидаемый jobId. Не включать их в Git, APK, BuildConfig, аргументы instrumentation или отчёт.

Сначала установить debug/test APK и передать fixtures в sandbox через stdin `adb exec-in run-as`. Затем:

```sh
adb shell am instrument -w -r \
  -e class com.meetspace.app.InstallerLiveTest -e installerLive 1 \
  com.meetspace.app.test/androidx.test.runner.AndroidJUnitRunner
```

Тест сверяет независимый fingerprint, сохраняет exact pin, получает подписанный plan существующей установки, читает status, проверяет idempotent resume, DataStore round trip, обнуление пароля операции, и показывает **фактически полученный** completed status в Compose. В finally удаляет pin и pending metadata. После проверки удалить private fixtures и VM. Production APK не содержит тестовый manifest, private key или тестовый CA.

## Результаты

Машиночитаемые итоговые результаты: `04_VERIFICATION.json`; public server evidence и screenshot: `evidence/04/`. Точные ограничения и исправленные отказы перечислены в `04_REMOTE_INSTALLER_REPORT.md`.
