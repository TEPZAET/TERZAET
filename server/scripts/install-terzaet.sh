#!/bin/sh
set -eu

repo="${TERZAET_REPO:-TEPZAET/TERZAET}"
ref="${TERZAET_REF:-main}"
install_dir="${TERZAET_INSTALL_DIR:-/opt/terzaet}"
container="terzaet-yandex"
control_container="terzaet-control"
image="terzaet-yandex:local"
doc_url="${TERZAET_DOC_URL:-${1:-}}"
encryption_key="${TERZAET_ENCRYPTION_KEY:-}"
key_file="$install_dir/encryption-key"
server_revision="2"
control_dir="$install_dir/control"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[ "$(id -u)" -eq 0 ] || fail "run as root"
[ -n "$doc_url" ] || fail "Yandex document URL is required"
case "$doc_url" in
    https://disk.yandex.*/*|https://yadi.sk/*) ;;
    *) fail "unsupported Yandex document URL" ;;
esac
[ -z "$encryption_key" ] || [ "${#encryption_key}" -ge 16 ] || fail "encryption key must contain at least 16 characters"
printf 'PROGRESS=5|Проверка сервера\n'

install_docker() {
    if command -v docker >/dev/null 2>&1; then
        return
    fi
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io ca-certificates curl tar
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y docker ca-certificates curl tar
    elif command -v yum >/dev/null 2>&1; then
        yum install -y docker ca-certificates curl tar
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache docker ca-certificates curl tar
    else
        fail "supported package manager not found"
    fi
    if command -v systemctl >/dev/null 2>&1; then
        systemctl enable --now docker
    else
        service docker start
    fi
}

install_docker
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable"
printf 'PROGRESS=15|Docker готов\n'

had_container=0
old_doc_url=""
if docker inspect "$container" >/dev/null 2>&1; then
    had_container=1
    old_image="$(docker inspect -f '{{.Image}}' "$container")"
    docker tag "$old_image" terzaet-yandex:rollback
    old_doc_url="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container" | sed -n 's/^URL=//p' | head -n 1)"
    if [ -f "$install_dir/document-url" ]; then
        old_doc_url="$(cat "$install_dir/document-url")"
    fi
fi

work_dir="$(mktemp -d /tmp/terzaet-install.XXXXXX)"
cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT INT TERM

archive="$work_dir/source.tar.gz"
printf 'PROGRESS=22|Загрузка серверной части\n'
curl -fL --retry 3 --connect-timeout 15 \
    "https://github.com/$repo/archive/refs/heads/$ref.tar.gz" -o "$archive"
mkdir "$work_dir/source"
tar -xzf "$archive" -C "$work_dir/source" --strip-components=1

printf 'PROGRESS=30|Сборка контейнера\n'
docker build -t "$image" "$work_dir/source/server"
printf 'PROGRESS=65|Контейнер собран\n'

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_dir="$install_dir/backups/$timestamp"
mkdir -p "$backup_dir"
[ ! -f "$key_file" ] || cp -p "$key_file" "$backup_dir/encryption-key"
printf 'PROGRESS=72|Резервная копия текущей установки\n'
legacy_active=0
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet openflux-yandex.service; then
    legacy_active=1
    systemctl cat openflux-yandex.service > "$backup_dir/openflux-yandex.service"
    if [ -f /usr/local/bin/openflux ]; then
        cp -p /usr/local/bin/openflux "$backup_dir/openflux"
    fi
    systemctl stop openflux-yandex.service
    systemctl disable openflux-yandex.service >/dev/null 2>&1 || true
fi

restore_previous() {
    docker rm -f "$container" >/dev/null 2>&1 || true
    if [ -f "$backup_dir/encryption-key" ]; then
        cp -p "$backup_dir/encryption-key" "$key_file"
    else
        rm -f "$key_file"
    fi
    if [ "$had_container" -eq 1 ] && [ -n "$old_doc_url" ]; then
        old_key_args=""
        if [ -f "$key_file" ]; then
            old_key_args="-v $key_file:/run/secrets/terzaet-key:ro -e ENCRYPTION_KEY_FILE=/run/secrets/terzaet-key"
        fi
        docker run -d --name "$container" --restart unless-stopped \
            --label app.terzaet.managed=true --cap-add NET_RAW --cap-add NET_ADMIN \
            -e ROLE=exit-node -e TRANSPORT=auto -e EXIT_MODE=l4 -e URL="$old_doc_url" \
            $old_key_args \
            terzaet-yandex:rollback >/dev/null
    elif [ "$legacy_active" -eq 1 ]; then
        systemctl enable --now openflux-yandex.service
    fi
}

mkdir -p "$install_dir"
mkdir -p "$control_dir"
if [ ! -s "$control_dir/admin.token" ]; then
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 36 | tr -d '\n' > "$control_dir/admin.token"
    else
        head -c 36 /dev/urandom | base64 | tr -d '\n' > "$control_dir/admin.token"
    fi
    chmod 600 "$control_dir/admin.token"
fi
if [ -n "$encryption_key" ]; then
    printf '%s\n' "$encryption_key" > "$key_file"
    chmod 600 "$key_file"
else
    rm -f "$key_file"
fi

docker rm -f "$container" >/dev/null 2>&1 || true
printf 'PROGRESS=80|Запуск TERZAET на сервере\n'
key_args=""
if [ -f "$key_file" ]; then
    key_args="-v $key_file:/run/secrets/terzaet-key:ro -e ENCRYPTION_KEY_FILE=/run/secrets/terzaet-key"
fi
if ! docker run -d \
    --name "$container" \
    --restart unless-stopped \
    --label app.terzaet.managed=true \
    --cap-add NET_RAW \
    --cap-add NET_ADMIN \
    -e ROLE=exit-node \
    -e TRANSPORT=auto \
    -e EXIT_MODE=l4 \
    -e URL="$doc_url" \
    $key_args \
    "$image" >/dev/null; then
    restore_previous
    fail "container start failed; previous service restored"
fi

sleep 5
printf 'PROGRESS=90|Проверка запуска\n'
if ! docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; then
    docker logs "$container" >&2 || true
    restore_previous
    fail "container stopped during startup; previous service restored"
fi

detected=""
attempt=0
while [ -z "$detected" ] && [ "$attempt" -lt 15 ]; do
    detected="$(docker logs "$container" 2>&1 | sed -n 's/.*Yandex document mode: \([^ ]*\).*/\1/p' | tail -n 1)"
    [ -n "$detected" ] || sleep 2
    attempt=$((attempt + 1))
done
[ -n "$detected" ] || detected="pending"
if docker logs "$container" 2>&1 | grep -qi 'captcha'; then
    docker logs "$container" >&2 || true
    restore_previous
    fail "Yandex CAPTCHA blocked this VDS IP; use another document or server IP"
fi
docker rm -f "$control_container" >/dev/null 2>&1 || true
if ! docker run -d \
    --name "$control_container" \
    --restart unless-stopped \
    --label app.terzaet.managed=true \
    -p 127.0.0.1:8787:8787 \
    -v "$control_dir:/opt/terzaet-control" \
    -e ROLE=control \
    -e CONTROL_LISTEN=127.0.0.1:8787 \
    -e CONTROL_DATA=/opt/terzaet-control/users.json \
    -e CONTROL_SECRET=/opt/terzaet-control/signing.key \
    -e CONTROL_TOKEN_FILE=/opt/terzaet-control/admin.token \
    "$image" >/dev/null; then
    fail "control service failed to start"
fi
printf '%s\n' "$doc_url" > "$install_dir/document-url"
chmod 600 "$install_dir/document-url"
printf '%s\n' "$server_revision" > "$install_dir/version"
chmod 600 "$install_dir/version"
printf 'PROGRESS=100|Сервер готов\n'
printf 'OK\nCONTAINER=%s\nCONTROL=%s\nTRANSPORT=%s\nBACKUP=%s\n' "$container" "$control_container" "$detected" "$backup_dir"
