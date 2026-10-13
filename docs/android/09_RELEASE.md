# Выпуск 0.9.0 (8) после аудита качества

Итог проверок и ограничения: [FINAL_QUALITY_AUDIT.md](FINAL_QUALITY_AUDIT.md). Артефакты этого этапа предназначены для проверки владельцем. Публикация, настройка Firebase и изменение production не выполняются автоматически.

## Сборка и проверка

JDK17, Android compile SDK36, Build Tools35.0.0, Gradle Wrapper8.13. `JAVA_HOME` и `ANDROID_HOME` должны указывать на установленные инструменты.

```sh
./gradlew testDebugUnitTest lintDebug lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease
python3 -m unittest discover -s installer/tests -p 'test_*.py' -v
python3 -m unittest discover -s testing/stage09 -p 'test_*.py' -v
python3 testing/stage09/verify_artifacts.py
```

Последняя команда проверяет **unsigned/Firebase-off** поставку. Нужен официальный bundletool1.18.3 с закреплённым SHA из [testing/stage09/README.md](../../testing/stage09/README.md). `artifact-verification.json` содержит фактические SHA и размеры пакетов, licenses, manifest, подписи, ABI/ELF и проверку известных private inputs. Она не заменяет runtime тесты или полноценный vulnerability scan.

- `app/build/outputs/apk/debug/app-debug.apk` — обычная debug-сборка; LeakCanary по умолчанию исключён.
- `app/build/outputs/apk/release/app-release-unsigned.apk` — minified release APK без постоянной подписи.
- `app/build/outputs/bundle/release/app-release.aab` — release bundle без постоянной подписи.
- `app/build/outputs/mapping/release/mapping.txt` — сохранить вместе с конкретной подписанной версией для расшифровки crash traces.

## Диагностика утечек

Только на отдельном эмуляторе с одноразовыми аккаунтами:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmeetspaceLeakCanary=true
```

Установить оба APK этого варианта и явно включить `quality09=true`, `qualityLeakCanary=true` в recordings lifecycle harness. Детали: [09_LIFECYCLE_AUDIT.md](09_LIFECYCLE_AUDIT.md). HPROF может содержать секреты и содержимое аккаунта; его нельзя публиковать вместе с обычными test reports. Для сравнения памяти baseline/after и поставки заново собрать без свойства. LeakCanary не входит в release dependency graph.

## Постоянная подпись и распространение

Постоянный keystore в этом этапе не создаётся. Владелец использует свой существующий release key либо создаёт и резервирует новый ключ в защищённом хранилище. Подробная процедура и четыре переменные `MEETSPACE_KEYSTORE`, `MEETSPACE_KEY_ALIAS`, `MEETSPACE_STORE_PASSWORD`, `MEETSPACE_KEY_PASSWORD` описаны в [08_RELEASE.md](08_RELEASE.md). Не помещать пароли в Gradle/source, shell history или workflow YAML. Все четыре значения обязательны вместе.

С подписью Gradle создаёт `app-release.apk` вместо unsigned APK. После этого нужны новые SHA, `apksigner verify`, проверка AAB и установка/обновление именно подписанного пакета на тестовом устройстве. Проверка unsigned-артефактов и временно подписанный smoke APK не доказывают эту процедуру. CI этого этапа не подписывает и не публикует приложение.

## Условия перед внешним выпуском

- Владелец принимает остаточные ограничения финального QA, в том числе native GNU_RELRO замечания и фактически проверенные ABI/API.
- Проверены физические устройства, низкая память, Bluetooth/headset, OEM restrictions и необходимые версии Android; эмулятор API36 не заменяет эту матрицу.
- На отдельном разрешённом VPS проведены install/upgrade/backup/restore/rollback и аварийное восстановление. Локальные fault tests не заменяют systemd/DNS/ACME/Docker rehearsal.
- Если нужен push: предоставлены Firebase config и backend gateway, повторены доставка/отзыв токена/доступ к файлам. В текущей сборке FCM отключён.
- Заполнены реальные сведения о хранении и удалении данных, поддержке и политика по [08_PRIVACY.md](08_PRIVACY.md), подготовлены сведения для магазина.
- Непосредственно перед распространением повторены проверки зависимостей/секретов на окончательном подписанном пакете, сохранены ключи, mapping и SHA.

Это список невыполненных внешних условий выпуска, а не заявление о готовности production.

## Результат проверки пакетов этапа09

Окончательные0.9.0(8) APK/AAB прошли [artifact verification](evidence/09/artifact-verification.json) с hash-bound strict ARM64/16KiB runtime. Статус `STATIC_PACKAGING_CHECKS_PASSED_WITH_RELRO_FINDINGS` сохраняет native GNU_RELRO замечания; x86_64 runtime не проверен. Обычная поставка также проверена на отсутствие известных LeakCanary/Shark и quality test class markers ([evidence](evidence/09/normal-diagnostics-exclusion.json)). Установка временно подписанного APK из AAB подтвердила launch и split-screen onboarding, а не release authentication/call E2E.
