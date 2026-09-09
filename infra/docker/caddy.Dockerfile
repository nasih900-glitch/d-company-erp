# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Build the exact standard Caddy v2.11.4 module set with the patched Go
# toolchain and dependency graph recorded under caddy-build/. The single-job
# build bounds peak RAM on the production VM.
FROM golang:1.26.8-alpine3.24@sha256:ce864e7223ac17b1775e6fd0b4c0db580c2eb50e7953a427916379e4b92a1628 AS caddy-builder

ARG TARGETOS
ARG TARGETARCH

ENV GOTOOLCHAIN=local \
    GOPROXY=https://proxy.golang.org \
    GOSUMDB=sum.golang.org \
    GOVCS=*:off

WORKDIR /src
COPY infra/docker/caddy-build/go.mod infra/docker/caddy-build/go.sum ./

RUN set -eux; \
    test "${TARGETOS}" = "linux"; \
    test "${TARGETARCH}" = "amd64"; \
    test "$(go env GOVERSION)" = "go1.26.8"; \
    go mod download; \
    go mod verify; \
    test "$(go list -mod=readonly -m -f '{{.Version}}' github.com/caddyserver/caddy/v2)" = "v2.11.4"; \
    test "$(go list -mod=readonly -m -f '{{.Version}}' golang.org/x/crypto)" = "v0.56.0"; \
    test "$(go list -mod=readonly -m -f '{{.Version}}' golang.org/x/net)" = "v0.58.0"; \
    test "$(go list -mod=readonly -m -f '{{.Version}}' golang.org/x/text)" = "v0.41.0"; \
    test "$(go list -mod=readonly -m -f '{{.Version}}' google.golang.org/grpc)" = "v1.83.2"

COPY infra/docker/caddy-build/main.go ./

RUN set -eux; \
    echo 'ac320c3ac47ad8abed7a0d2595639d78f9ff55cf1dba8494a66cd8851c5d076e  main.go' | sha256sum -c -; \
    CGO_ENABLED=0 GOOS="${TARGETOS}" GOARCH="${TARGETARCH}" GOMAXPROCS=1 GOMEMLIMIT=768MiB \
      go build -p=1 -mod=readonly -buildvcs=false \
        -tags='nobadger,nomysql,nopgx' \
        -trimpath \
        -ldflags='-s -w -buildid= -X github.com/caddyserver/caddy/v2.CustomVersion=v2.11.4-dcompany.1' \
        -o /out/caddy .; \
    echo '951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac  /out/caddy' | sha256sum -c -; \
    go version -m /out/caddy | grep -F 'go1.26.8'; \
    go version -m /out/caddy | grep -F 'github.com/caddyserver/caddy/v2'; \
    go version -m /out/caddy | grep -F 'v2.11.4'

# Retain the exact upstream 2.11.4 runtime contract and replace only the Go
# binary. Exact package pins make future repository drift fail closed; the
# release gate must still scan the completed image.
FROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648

ARG APP_VERSION=dev
ARG APP_REVISION=unknown

LABEL org.opencontainers.image.title="D Company ERP Caddy" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
      org.opencontainers.image.source="https://github.com/caddyserver/caddy" \
      com.dcompany.upstream.version="v2.11.4"

RUN set -eux; \
    apk add --no-cache --upgrade \
      'c-ares=1.34.8-r0' \
      'curl=8.22.0-r0' \
      'libcurl=8.22.0-r0' \
      'libcrypto3=3.5.8-r0' \
      'libssl3=3.5.8-r0'; \
    installed_packages="$(apk info -v)"; \
    printf '%s\n' "$installed_packages" | grep -Fx 'c-ares-1.34.8-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'curl-8.22.0-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcurl-8.22.0-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.5.8-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.5.8-r0'

COPY --from=caddy-builder /out/caddy /usr/bin/caddy

RUN set -eux; \
    chmod 0755 /usr/bin/caddy; \
    setcap cap_net_bind_service=+ep /usr/bin/caddy; \
    caddy version | grep -F 'v2.11.4-dcompany.1'
