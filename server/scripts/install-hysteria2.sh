#!/usr/bin/env sh
set -eu

base_dir="${TERZAET_HY_DIR:-/opt/terzaet-hysteria}"
host="${TERZAET_HY_HOST:?TERZAET_HY_HOST is required}"
version="v2.12.3"
port="${TERZAET_HY_PORT:-443}"
container="terzaet-hysteria"
image="terzaet-hysteria:local"

case "$(uname -m)" in
  x86_64|amd64) asset="hysteria-linux-amd64" ;;
  aarch64|arm64) asset="hysteria-linux-arm64" ;;
  armv7l|armv7) asset="hysteria-linux-arm" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

install_openssl() {
  command -v openssl >/dev/null 2>&1 && return
  if command -v apt-get >/dev/null 2>&1; then apt-get update -y && apt-get install -y openssl
  elif command -v dnf >/dev/null 2>&1; then dnf install -y openssl
  elif command -v yum >/dev/null 2>&1; then yum install -y openssl
  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssl
  else echo "OpenSSL is required" >&2; exit 1
  fi
}

download() {
  url="$1"
  output="$2"
  temporary="${output}.download.$$"
  rm -f "$temporary"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$url" -o "$temporary"
  elif command -v wget >/dev/null 2>&1; then wget -qO "$temporary" "$url"
  else echo "curl or wget is required" >&2; exit 1
  fi
  mv -f "$temporary" "$output"
}

install_openssl
command -v docker >/dev/null 2>&1 || { echo "Docker is required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon is unavailable" >&2; exit 1; }
mkdir -p "$base_dir"
download "https://github.com/HyNetworks/hysteria/releases/download/app/${version}/${asset}" "$base_dir/hysteria"
download "https://github.com/HyNetworks/hysteria/releases/download/app/${version}/hashes.txt" "$base_dir/hashes.txt"
expected="$(awk -v item="build/${asset}" '$2 == item { print $1 }' "$base_dir/hashes.txt")"
actual="$(sha256sum "$base_dir/hysteria" | awk '{print $1}')"
[ -n "$expected" ] && [ "$actual" = "$expected" ] || { echo "Hysteria checksum verification failed" >&2; exit 1; }
chmod 700 "$base_dir/hysteria"

if [ ! -s "$base_dir/server.key" ] || [ ! -s "$base_dir/server.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 3650 -keyout "$base_dir/server.key" -out "$base_dir/server.crt" -subj "/CN=${host}" >/dev/null 2>&1
fi

if [ -s "$base_dir/auth" ]; then
  auth="$(cat "$base_dir/auth")"
else
  auth="$(openssl rand -hex 24)"
fi
printf '%s' "$auth" > "$base_dir/auth"
chmod 600 "$base_dir/auth" "$base_dir/server.key"
cat > "$base_dir/config.yaml" <<EOF
listen: :${port}
tls:
  cert: ${base_dir}/server.crt
  key: ${base_dir}/server.key
  sniGuard: disable
auth:
  type: password
  password: ${auth}
EOF

cat > "$base_dir/Dockerfile" <<EOF
FROM alpine:3.20
COPY hysteria /usr/local/bin/hysteria
RUN chmod 700 /usr/local/bin/hysteria
ENTRYPOINT ["/usr/local/bin/hysteria"]
EOF

docker build -t "$image" "$base_dir" >/dev/null
systemctl disable --now terzaet-hysteria.service >/dev/null 2>&1 || true
rm -f /etc/systemd/system/terzaet-hysteria.service
systemctl daemon-reload >/dev/null 2>&1 || true
docker rm -f "$container" >/dev/null 2>&1 || true
docker run -d --name "$container" --restart unless-stopped \
  --label app.terzaet.managed=true \
  -p "${port}:${port}/udp" \
  -v "$base_dir:$base_dir:ro" \
  "$image" server -c "$base_dir/config.yaml" >/dev/null
sleep 2
docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -q true || { docker logs "$container" >&2 || true; exit 1; }
fingerprint="$(openssl x509 -noout -fingerprint -sha256 -in "$base_dir/server.crt" | cut -d= -f2 | tr -d ':')"
printf 'HY2_URI=hysteria2://%s@%s:%s/?insecure=1&pinSHA256=%s\n' "$auth" "$host" "$port" "$fingerprint"
printf 'OK\n'
