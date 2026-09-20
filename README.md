# TERZAET

TERZAET — независимый форк [OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid) с обновлённым Android-интерфейсом и установкой серверной части на VDS.

## Android

Готовый APK находится в разделе **Releases**.

Требования:

- Android 8.0 или новее;
- разрешение на VPN-подключение;
- разрешение на уведомления для отображения состояния и переподключения.

Сборка из исходников:

```bash
./gradlew assembleDebug
```

Android namespace `io.github.p1neapplexpress.openflux` сохранён для совместимости с установленными версиями форка. Встроенный клиент OpenFlux собран из commit `9148c6356a0f580b46c0af92afbc42fd96be64c8` с Go 1.27.0. Скрипты повторной сборки нативных компонентов находятся в `app/src/main/build-openflux.sh` и `app/src/main/build-jni.sh`; для них требуется Android NDK r27 или новее.

## Установка на VDS

Самый простой способ — открыть вкладку **Админ** в приложении и указать адрес VDS, пользователя `root`, пароль SSH, ссылку на документ Яндекса и при необходимости ключ шифрования.

Приложение проверит сервер, установит Docker, соберёт серверную часть и добавит конфигурацию автоматически.

Ручная установка:

```bash
export TERZAET_DOC_URL='https://disk.yandex.ru/i/...'
curl -fsSL https://raw.githubusercontent.com/TEPZAET/TERZAET/main/server/scripts/install-terzaet.sh | sudo -E sh
```

С дополнительным шифрованием:

```bash
export TERZAET_DOC_URL='https://disk.yandex.ru/i/...'
export TERZAET_ENCRYPTION_KEY='your-secret-key-at-least-16-characters'
curl -fsSL https://raw.githubusercontent.com/TEPZAET/TERZAET/main/server/scripts/install-terzaet.sh | sudo -E sh
```

Поддерживаются системы с `apt`, `dnf`, `yum` или `apk`. Установщик не изменяет и не удаляет Amnezia VPN.

## Происхождение

TERZAET является неофициальным независимым форком OpenFluxAndroid и OpenFlux. Проект не связан с авторами оригинальных проектов. Права на исходные компоненты, зависимости и сторонний код принадлежат их соответствующим авторам. Код предоставляется как есть, без гарантий.

---

TERZAET is an independent, unofficial fork of [OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid) with a redesigned Android interface and VDS deployment support.

The APK is available under **Releases**. Build the Android project with `./gradlew assembleDebug`. Server sources and the VDS installer are located in [`server`](server). TERZAET is not affiliated with the original OpenFlux authors and is provided as is, without warranty.
