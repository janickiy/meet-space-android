# Этап 03: воспроизводимая проверка SSH на отдельной Linux VM

## Назначение и текущий статус

`SshLiveIntegrationTest` проверяет настоящий OpenSSH, передачу read-only preflight-пробы и поведение Android SSH-репозитория. Тесты не обращаются к production MeetSpace и не используют HTTP-аккаунты. Они запускаются только с явным аргументом `sshLive=1` и временными файлами внутри sandbox debug-приложения.

Фактический запуск 11 октября 2026 года: repository suite **7/7 PASS** (10,7 с), отдельные unknown/changed host-key проверки **2/2 PASS**, финальный live UI wizard **1/1 PASS** (5,4 с), SSH Compose suite **6/6 PASS** (7,0 с). Приложение подключалось к отдельной Ubuntu 24.04 ARM64 VM с OpenSSH 9.6p1. Независимый `sshd` log подтвердил `authenticationDelta=0` для обоих недоверенных сценариев; SSH/sudo configurations и package manifest VM не изменились за время preflight-тестов. Секреты не обнаружены в instrumentation stdout: host harness проверяет его до сохранения и публикации. Дополнительная проверка свежего logcat и build/evidence/test logs также не нашла fixture secrets. После визуального просмотра скриншотов lifecycle cleanup выполнен и проверен.

## Предварительные условия

- macOS Apple Silicon, установленный Lima; в проверенном окружении используется Lima 1.0.0 / драйвер `vz`.
- Android SDK / `adb`, запущенный ARM64-эмулятор, debug APK приложения и APK instrumentation-тестов.
- Свободные CPU/RAM/disk для **новой** одноразовой VM. Проверенный профиль: Ubuntu 24.04 ARM64, 2 CPU, 2 GiB RAM, диск 12 GiB.
- OpenSSH 9.6 или новее внутри VM: transport требует modern strict KEX. Проверенный сервер: OpenSSH 9.6p1.

Сначала выполнить только инвентаризацию:

```sh
limactl list --json
adb devices
```

Не использовать существующую VM, SSH-аккаунт, production VPS или пользовательские ключи. Если выбранное имя VM уже занято, выбрать другое и изменить его только в тестовых helper-скриптах. Не сбрасывать и не переустанавливать чужой instance.

## Отдельная тестовая инфраструктура

В этом workspace helper-скрипты и временные файлы находятся в `.audit/ssh-stage03/`; `.audit/` исключён из Git. Это локальная test infra, а не часть приложения. Каталог имеет mode `700`, private keys и файлы с парольными данными — `600`.

Helper-скрипты:

| Файл | Действие |
| --- | --- |
| `meetspace-stage03.yaml` | Новая Ubuntu ARM64 VM, без host mounts, контейнерного runtime, SSH agent forwarding и загрузки пользовательских `~/.ssh/*.pub`. |
| `create-fixtures.py` | Генерирует одноразовый пароль, encrypted OpenSSH RSA-3072 / Ed25519 client keys, passphrases, два независимых Ed25519 host keys. Отказывается перезаписывать уже созданные секреты. |
| `bootstrap.sh`, `configure-vm.py`, `start-services.sh` | Устанавливают пакеты и создают тестовый Linux-аккаунт **только внутри новой VM**, запускают две отдельные `sshd` и TCP listener без SSH-ответа для cancellation. |
| `push-app-fixtures.py` | Передаёт private runtime files через `adb run-as` / stdin; устанавливает directory `700`, files `600`. |
| `run-integration.py` | Запускает проверки неизвестного/изменённого host key отдельно, сравнивает auth-счётчики `sshd`, затем запускает весь repository suite. |

Если локальных helper-файлов нет, воссоздать их в новом ignored-каталоге по следующей конфигурации. Код приложения не должен содержать fixture credentials или автоматически запускать эту инфраструктуру.

1. Скачать официальный [Ubuntu 24.04 ARM64 cloud image](https://cloud-images.ubuntu.com/releases/noble/release/) и сверить его digest с [SHA256SUMS](https://cloud-images.ubuntu.com/releases/noble/release/SHA256SUMS). Указать проверенный digest в Lima YAML; не отключать проверку digest.
2. Создать отдельный Lima instance с указанными выше ресурсами, `mounts: []`, `ssh.loadDotSSHPubKeys: false`, `ssh.forwardAgent: false`, `containerd.system: false`, `containerd.user: false`. Административный SSH-порт и все тестовые forwards слушают только `127.0.0.1` хоста.
3. Внутри этой VM установить `openssh-server`, `python3`, `curl`, `ca-certificates`, `util-linux`; создать отдельного пользователя с домашним каталогом, password authentication и двумя fixture public keys в `authorized_keys`. При необходимости проверки `sudo -n` дать права только этому disposable пользователю внутри disposable VM. Не менять основной Lima `sshd`.
4. Запустить fixture `sshd` на guest-портах `2222` и `2223`: разные host keys, `PermitRootLogin no`, `KbdInteractiveAuthentication no`, `AllowUsers` только fixture user, `LogLevel VERBOSE`, отдельные log/PID files. На guest `2224` запустить TCP listener, который принимает соединение, не отправляет SSH-баннер и закрывает сокет при отключении клиента.
5. Создать loopback forwards. В проверенном окружении Lima 1.0 automatic forwards не возвращали баннер; использован отдельный SSH tunnel с **проверкой admin host key** и `StrictHostKeyChecking=yes`. Административный public host key получают независимо из новой VM, записывают в отдельный test `known_hosts`. Никакой fixture authentication в Android не использует обход host key verification.

Проверенная схема tunnel: host `22213 → guest 2222`, host `22214 → guest 2223`, host `22215 → guest 2224`. Это test infra; приложение принимает произвольные введённые endpoint и port. С Android-эмулятора адрес хоста — `10.0.2.2`. На физическом устройстве следует обеспечить отдельный доступ к test infra и изменить private runtime fixture, не открывая production-сервер.

Пример локального порядка подготовки с helper-файлами:

```sh
umask 077
mkdir -p .audit/ssh-stage03
chmod 700 .audit/ssh-stage03
python3 .audit/ssh-stage03/create-fixtures.py
limactl start --tty=false --name=meetspace-stage03 .audit/ssh-stage03/meetspace-stage03.yaml
python3 .audit/ssh-stage03/configure-vm.py
```

После настройки независимо получить public keys fixture `sshd`, вычислить fingerprints `SHA256:` от декодированного SSH public-key blob и добавить их в private runtime properties. Fingerprint основного сервера сравнивается live UI-тестом с тем, что показывает приложение, до установки checkbox доверия. Это не fingerprint, выдуманный интерфейсом.

Если нужны отдельные SSH tunnels, сначала получить admin public host key через локальный канал управления **новой** VM и записать его в test `known_hosts` с endpoint `[127.0.0.1]:22103`. Задать `MS_SSH_ADMIN_USER` и `MS_SSH_ADMIN_IDENTITY` из локальной Lima-конфигурации, затем открыть tunnel:

```sh
ssh -N -T -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=.audit/ssh-stage03/admin.known_hosts \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 \
  -i "$MS_SSH_ADMIN_IDENTITY" -p 22103 \
  -L 127.0.0.1:22213:127.0.0.1:2222 \
  -L 127.0.0.1:22214:127.0.0.1:2223 \
  -L 127.0.0.1:22215:127.0.0.1:2224 \
  "${MS_SSH_ADMIN_USER}@127.0.0.1"
```

Этот control identity не передаётся приложению и не используется в fixture tests. Сохранить PID созданного tunnel для последующего адресного завершения. Не закрывать чужие SSH tunnels по общему имени процесса.

## Private runtime fixture

`targetContext.filesDir/ssh-stage03/private-fixture.properties` содержит Java Properties со следующими полями. Показанные ниже значения — placeholders; реальные пароли и ключи не включаются в этот документ:

```properties
host=10.0.2.2
port=<forward-to-primary-sshd>
alternatePort=<forward-to-alternate-sshd>
stallPort=<forward-to-stalled-listener>
user=<disposable-linux-user>
password=<one-time-generated-password>
rsaPassphrase=<one-time-generated-rsa-passphrase>
ed25519Passphrase=<one-time-generated-ed25519-passphrase>
domain=example.invalid
primaryKeyAlgorithm=ssh-ed25519
primaryKeyBase64=<independently-obtained-public-key-blob>
primaryKeyFingerprint=SHA256:<independently-computed-public-fingerprint>
alternateKeyAlgorithm=ssh-ed25519
alternateKeyBase64=<alternate-public-key-blob>
alternateKeyFingerprint=SHA256:<alternate-public-fingerprint>
```

В том же каталоге находятся `client_rsa` и `client_ed25519` — encrypted OpenSSH private key files. Пароль, passphrase и private key не передают через runner arguments, `BuildConfig`, исходники, сообщения UI или stdout. Private fixture передают в sandbox через stdin и `run-as`, например helper `push-app-fixtures.py`, **после** установки APK и очистки debug app state.

Неразрешаемый reserved домен нужен намеренно: DNS/TLS readiness должен сообщить WARN, а не притворяться готовым HTTPS. В фактическом запуске использован `stage03.test`; `example.invalid` выше — пример другой reserved зоны. Для CPU/RAM/disk маленькой VM ожидается WARN; отсутствие Docker/Compose также является WARN и не запускает установку. Tests не требуют «все проверки зелёные».

## Запуск и доказательство отсутствия ранней авторизации

APK собирать и устанавливать отдельно; параллельные Gradle-запуски в одном checkout не нужны. После подготовки VM и установки app/test APK:

```sh
adb shell pm clear com.meetspace.app
python3 .audit/ssh-stage03/push-app-fixtures.py
python3 .audit/ssh-stage03/run-integration.py
```

Отдельный запуск без helper:

```sh
adb shell am instrument -w \
  -e sshLive 1 \
  -e class com.meetspace.app.SshLiveIntegrationTest \
  com.meetspace.app.test/androidx.test.runner.AndroidJUnitRunner
```

Без `sshLive=1` тесты пропускаются. Без private fixture они также пропускаются. Обычные unit/Compose-тесты не требуют VM и не загружают её автоматически.

Repository suite содержит семь сценариев:

1. NEW host key получается handshake-only, до password/public-key authentication.
2. После явного pin password preflight возвращает фактические Ubuntu / ARM64 / CPU / RAM / disk / sudo checks; password bytes очищены.
3. Encrypted OpenSSH RSA private key с passphrase проходит настоящую SSH-авторизацию; credential bytes очищены.
4. То же для encrypted OpenSSH Ed25519.
5. Неверный пароль возвращает безопасный `AUTH`; error text не содержит fixture secrets.
6. При расхождении с ранее сохранённым независимым host key inspect возвращает CHANGED; preflight блокируется до auth, повторное TOFU-подтверждение запрещено.
7. Внешняя coroutine cancellation прерывает зависший handshake быстрее SSH timeout и очищает credential bytes.

Проверки NEW и CHANGED дополнительно запускаются отдельно. Перед/после каждого сценария host harness считывает **число**, а не содержимое секретов, строк `Accepted password`, `Accepted publickey`, `Failed password`, `Failed publickey` из выделенного `sshd` log. `authenticationDelta` должен быть `0`. Одного UI-сообщения для такого доказательства недостаточно.

`run-integration.py` сохраняет sanitized `unknown-host-test.log`, `changed-host-test.log`, `live-integration.log` и `no-auth-proof.json`. Отчёт не должен копировать private fixture files.

Отдельный `SshWizardLiveUiTest` проходит реальный путь welcome → SSH details → fingerprint comparison → password → preflight → results, проверяет пересоздание экрана и выходит из wizard. Screenshots разрешены на details/fingerprint/results после очистки password field; auth form с введённым secret не снимают.

Запуск live UI после repository suite:

```sh
python3 .audit/ssh-stage03/run-integration.py \
  com.meetspace.app.SshWizardLiveUiTest live-ui.log
```

Фактические безопасные screenshots находятся в `docs/android/screenshots/stage03-details.png`, `stage03-fingerprint.png`, `stage03-results.png`. На странице fingerprint live UI-тест сравнивает показанный ключ с независимым public key VM до разрешения доверия. На странице результатов отображаются реальные Ubuntu/ARM64 checks и ожидаемые WARN для небольшой VM без Docker/Compose; установка не выполнялась.

Дополнительно сохранены `stage03-details-narrow-large-font.png` и `stage03-details-narrow-large-font-scrolled.png`: доступная ширина 420 dp, `fontScale=1.5`, начальная форма без передачи SSH credentials и без network operations. Второй screenshot показывает доступность кнопки и пояснений после прокрутки. После capture восстановлены исходные размер/density/font settings эмулятора.

## Проверка логов и cleanup

До удаления fixture проверить app logcat, build/instrumentation/evidence logs на совпадение с реальными fixture password/passphrases и private-key payload. Проверку выполнять программно; выводить только количество совпадений и имена файлов, никогда matched contents. При совпадении удалить/очистить соответствующий artifact и исправить источник утечки до доставки.

После завершения всех repository/UI tests:

1. Удалить private fixture и screenshots из debug app sandbox; сбросить только fixture host pins. При необходимости очистить данные **тестового** приложения.
2. Завершить отдельно созданный SSH tunnel и удалить новый disposable Lima instance:

   ```sh
   limactl stop --tty=false meetspace-stage03
   limactl delete --tty=false meetspace-stage03
   ```

3. Удалить временные passwords, passphrases, client/host private keys и временные credential files с хоста. Сохранить только sanitized журналы, auth-count proof и публичную конфигурацию для отчёта.
4. Проверить `limactl list --json` и отсутствие test forwards. Другие VM, глобальные SSH-ключи пользователя, Android SDK и production resources не изменять.

Полный lifecycle cleanup выполняется после последнего теста. До этого VM и private fixtures нужны для повторного воспроизведения ошибок.

### Фактически выполненный cleanup

После финальных тестов и просмотра пяти безопасных screenshots:

- Свежий logcat: **0** совпадений с fixture passwords/passphrases/private-key payload. Перед удалением секретов повторно проверены 152 сохранённых log/JSON/XML/text artifacts: **0** совпадений. Значения секретов не печатались.
- Очищены данные только debug test application `com.meetspace.app`. Проверено отсутствие private fixture directory и `meetspace_ssh_host_pins` DataStore.
- Завершён только отдельно созданный SSH tunnel; instance `meetspace-stage03` остановлен и удалён. `limactl list --json` снова не содержит VM — как и до начала теста.
- На loopback не осталось слушателей test/control ports `22103`, `22203`–`22205`, `22213`–`22215`.
- Удалены одноразовые credential properties/JSON, `chpasswd.input`, client/fixture-host private keys. Sanitized evidence, helper source и public configuration сохранены. Общий control identity Lima проверен по digest до/после cleanup и не изменён.
- Восстановлены исходные настройки эмулятора: physical size `2560x1600`, density `320`, `fontScale=1.0`; size/density overrides отсутствуют.

Sanitized proof files этого запуска: `.audit/ssh-stage03/no-auth-proof.json`, `.audit/ssh-stage03/secret-scan-summary.json`, `.audit/ssh-stage03/cleanup-summary.json`. Private runtime files удалены; повторный live запуск требует новой disposable VM и заново сгенерированных credentials.
