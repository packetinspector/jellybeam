#!/usr/bin/env bash
# Dev Jellyseerr beside the dev Jellyfin container (docs/05 "Test servers"): starts
# fallenbagel/jellyseerr on the dev server's docker network, initializes it against the
# synthetic jellybeam-admin account, and creates one synthetic Seerr local account so the
# "Seerr login" method can be exercised (core/ffi/tests/live_seerr_local.rs, emulator).
# Idempotent: re-running keeps the existing config. Everything here is synthetic.
#
# Second instance on the current Seerr line, against the Jellyfin 12 container:
#   SEERR_IMAGE=seerr/seerr:latest SEERR_CONTAINER=jellybeam-dev-seerr SEERR_PORT=5056 \
#   JELLYFIN_CONTAINER=jellybeam-dev-jellyfin12 SEERR_CONFIG_DIR=internal/dev-seerr3-config \
#   tools/dev-seerr/up.sh
set -euo pipefail

NAME="${SEERR_CONTAINER:-jellybeam-dev-jellyseerr}"
IMAGE="${SEERR_IMAGE:-fallenbagel/jellyseerr:latest}"
JELLYFIN_CONTAINER="${JELLYFIN_CONTAINER:-jellybeam-dev-jellyfin}"   # 10.10.x; Jellyseerr 2.7 cannot set up against 12.0
NETWORK="${SEERR_NETWORK:-$(docker inspect "$JELLYFIN_CONTAINER" --format '{{.HostConfig.NetworkMode}}')}"
PORT="${SEERR_PORT:-5055}"
CONFIG_DIR="${SEERR_CONFIG_DIR:-$(cd "$(dirname "$0")/../.." && pwd)/internal/dev-seerr-config}"
API="http://localhost:${PORT}/api/v1"
ADMIN_USER="jellybeam-admin"
PASSWORD="jellybeam-test"
LOCAL_EMAIL="local@example.test"
LOCAL_USERNAME="local-user"

mkdir -p "$CONFIG_DIR"
if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    # Proxy variables are cleared: a Docker-wide proxy turns every in-network call into a 502.
    docker run -d --name "$NAME" --network "$NETWORK" -p "127.0.0.1:${PORT}:5055" \
        -e JELLYFIN_TYPE=jellyfin -e HTTP_PROXY= -e HTTPS_PROXY= -e http_proxy= -e https_proxy= \
        -e ALL_PROXY= -e all_proxy= -v "$CONFIG_DIR:/app/config" "$IMAGE" >/dev/null
fi
for _ in $(seq 1 45); do
    curl -s -m 2 "$API/status" >/dev/null && break
    sleep 2
done

initialized=$(curl -s "$API/settings/public" | python3 -c 'import sys,json; print(json.load(sys.stdin)["initialized"])')
jar=$(mktemp)
trap 'rm -f "$jar"' EXIT
if [ "$initialized" != "True" ]; then
    curl -s -f -c "$jar" -H 'Content-Type: application/json' -X POST "$API/auth/jellyfin" -d "$(printf \
        '{"username":"%s","password":"%s","hostname":"%s","port":8096,"useSsl":false,"urlBase":"","email":"admin@example.test","serverType":2}' \
        "$ADMIN_USER" "$PASSWORD" "$JELLYFIN_CONTAINER")" >/dev/null
    curl -s -f -b "$jar" -X POST "$API/settings/initialize" >/dev/null
else
    curl -s -f -c "$jar" -H 'Content-Type: application/json' -X POST "$API/auth/jellyfin" \
        -d "$(printf '{"username":"%s","password":"%s"}' "$ADMIN_USER" "$PASSWORD")" >/dev/null
fi
if ! curl -s -b "$jar" "$API/user?take=100" | grep -q "\"email\":\"$LOCAL_EMAIL\""; then
    curl -s -f -b "$jar" -H 'Content-Type: application/json' -X POST "$API/user" -d "$(printf \
        '{"email":"%s","username":"%s","password":"%s","permissions":32}' "$LOCAL_EMAIL" "$LOCAL_USERNAME" "$PASSWORD")" >/dev/null
fi
# The admin API key (synthetic dev instance) for the API-key method; read by the live test.
curl -s -f -b "$jar" "$API/settings/main" | python3 -c 'import sys,json; print(json.load(sys.stdin)["apiKey"], end="")' > "$CONFIG_DIR/api-key"
echo "Seerr ($IMAGE) ready at http://localhost:${PORT} (emulator: http://10.0.2.2:${PORT})"
echo "API key written to ${CONFIG_DIR}/api-key (ignored path; never commit or echo it)"
echo "Seerr local account: ${LOCAL_EMAIL} / ${PASSWORD}   (username ${LOCAL_USERNAME} is NOT accepted by /auth/local)"
