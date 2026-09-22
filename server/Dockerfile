# OpenFlux (openflux) — one image, two roles:
#   client    — SOCKS5 proxy, no special privileges
#   exit-node — raw sockets + RST-drop, needs NET_RAW/NET_ADMIN (see compose)
# Role is selected at runtime by the entrypoint from ROLE=client|exit-node.

FROM golang:1.26.4-alpine AS build
WORKDIR /src

# Cache module downloads across builds.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/openflux .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/terzaet-control ./cmd/terzaet-control

FROM alpine:3.22
# ca-certificates: all transports are TLS (wss/https) to Yandex/MAX endpoints.
# iptables: the exit node must drop kernel RSTs inside its network namespace.
RUN apk add --no-cache ca-certificates iptables

COPY --from=build /out/openflux /usr/local/bin/openflux
COPY --from=build /out/terzaet-control /usr/local/bin/terzaet-control
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/openflux

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
