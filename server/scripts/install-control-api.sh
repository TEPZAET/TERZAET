#!/usr/bin/env sh
set -eu

repo="${TERZAET_REPO:-TEPZAET/TERZAET}"
ref="${TERZAET_REF:-feature/hysteria2-fallback}"
install_dir="${TERZAET_INSTALL_DIR:-/opt/terzaet}"
control_dir="$install_dir/control"
image="terzaet-yandex:local"
container="terzaet-control"

[ "$(id -u)" -eq 0 ] || { echo "Run as root" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon is unavailable" >&2; exit 1; }

has_control=0
if docker image inspect "$image" >/dev/null 2>&1; then
  docker run --rm --entrypoint /bin/sh "$image" -c 'test -x /usr/local/bin/terzaet-control' >/dev/null 2>&1 && has_control=1 || true
fi

if [ "$has_control" -ne 1 ]; then
  work_dir="$(mktemp -d /tmp/terzaet-control.XXXXXX)"
  trap 'rm -rf "$work_dir"' EXIT INT TERM
  archive="$work_dir/source.tar.gz"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 15 "https://github.com/$repo/archive/refs/heads/$ref.tar.gz" -o "$archive"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$archive" "https://github.com/$repo/archive/refs/heads/$ref.tar.gz"
  else
    echo "curl or wget is required" >&2
    exit 1
  fi
  mkdir "$work_dir/source"
  tar -xzf "$archive" -C "$work_dir/source" --strip-components=1
  docker build -t "$image" "$work_dir/source/server"
fi

mkdir -p "$control_dir"
if [ ! -s "$control_dir/admin.token" ]; then
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 36 | tr -d '\n' > "$control_dir/admin.token"
  else
    head -c 36 /dev/urandom | base64 | tr -d '\n' > "$control_dir/admin.token"
  fi
  chmod 600 "$control_dir/admin.token"
fi

docker rm -f "$container" >/dev/null 2>&1 || true
docker run -d --name "$container" --restart unless-stopped \
  --label app.terzaet.managed=true \
  -p 127.0.0.1:8787:8787 \
  -v "$control_dir:/opt/terzaet-control" \
  -e ROLE=control \
  -e CONTROL_LISTEN=0.0.0.0:8787 \
  -e CONTROL_DATA=/opt/terzaet-control/users.json \
  -e CONTROL_SECRET=/opt/terzaet-control/signing.key \
  -e CONTROL_TOKEN_FILE=/opt/terzaet-control/admin.token \
  "$image" >/dev/null

token="$(cat "$control_dir/admin.token")"
attempt=0
while [ "$attempt" -lt 15 ]; do
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -H "Authorization: Bearer $token" http://127.0.0.1:8787/v1/health >/dev/null 2>&1 && break
  elif command -v wget >/dev/null 2>&1; then
    wget -qO /dev/null --header="Authorization: Bearer $token" http://127.0.0.1:8787/v1/health && break
  fi
  sleep 1
  attempt=$((attempt + 1))
done
[ "$attempt" -lt 15 ] || { docker logs "$container" >&2 || true; echo "Control API did not become ready" >&2; exit 1; }
chmod 600 "$control_dir/admin.token" "$control_dir/signing.key"
printf 'CONTROL_API=ready\nOK\n'
