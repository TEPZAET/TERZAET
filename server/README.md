# TERZAET Server

Серверная часть TERZAET основана на OpenFlux и запускается в Docker.

Установка:

```bash
export TERZAET_DOC_URL='https://disk.yandex.ru/i/...'
curl -fsSL https://raw.githubusercontent.com/TEPZAET/TERZAET/main/server/scripts/install-terzaet.sh | sudo -E sh
```

Дополнительные переменные:

- `TERZAET_ENCRYPTION_KEY` — ключ длиной не менее 16 символов;
- `TERZAET_INSTALL_DIR` — каталог установки, по умолчанию `/opt/terzaet`;
- `TERZAET_REF` — ветка репозитория, по умолчанию `main`.

Установщик поддерживает `apt`, `dnf`, `yum` и `apk`, создаёт контейнер `terzaet-yandex` и сохраняет резервную копию предыдущей управляемой установки.

Исходный проект: https://github.com/p1neappleXpress/OpenFlux
