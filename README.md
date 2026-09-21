# TERZAET

TERZAET — независимый неофициальный форк OpenFluxAndroid/OpenFlux с отдельной серверной частью для VDS.

<table>
  <tr>
    <td align="center">
      <img src="docs/terzaet-home.jpg" alt="Главный экран TERZAET" width="320">
      <br>
      <sub>Главный экран</sub>
    </td>
    <td align="center">
      <img src="docs/terzaet-server.jpg" alt="Установка собственного сервера" width="320">
      <br>
      <sub>Установка собственного сервера</sub>
    </td>
  </tr>
</table>

Минималистичный интерфейс, понятные состояния подключения и управление сервером из одного места.

## Установка на VDS

Откройте в приложении раздел «Админ», укажите имя сервера, адрес VDS, пользователя, пароль SSH и публичную ссылку на документ Яндекса. При необходимости можно включить дополнительное шифрование.

Приложение проверит доступность VDS, подготовит Docker, установит TERZAET и добавит конфигурацию автоматически.

Для ручной установки на сервере:

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

TERZAET — независимый неофициальный форк [OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid) и [OpenFlux](https://github.com/p1neappleXpress/OpenFlux). Проект не связан с авторами оригинальных репозиториев. Права на исходные компоненты, зависимости и сторонний код принадлежат их соответствующим авторам. Код предоставляется как есть, без гарантий.
