#!/usr/bin/env bash
set -u

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MCAV_ROOT="${MCAV_ROOT:-$SCRIPT_ROOT}"
export MCAV_ROOT

log() {
  printf '[MCAV VJ] %s\n' "$*"
}

if [[ "${1:-}" != "--" ]]; then
  log 'ERROR: startup must include -- followed by the existing Paper command.' >&2
  log 'Example: bash mcav-vj/start-mcav.sh -- java -jar server.jar nogui' >&2
  exit 64
fi
shift
if [[ "$#" -eq 0 ]]; then
  log 'ERROR: no Paper command was supplied after --.' >&2
  exit 64
fi

PAPER_COMMAND=("$@")

launch_paper() {
  log 'Starting Paper in the foreground.'
  exec "${PAPER_COMMAND[@]}"
}

if [[ -f "$MCAV_ROOT/mcav.env" ]]; then
  # This optional file is deliberately administrator-owned configuration.
  set -a
  # shellcheck disable=SC1091
  source "$MCAV_ROOT/mcav.env"
  set +a
fi

if [[ -z "${MCAV_PUBLIC_HOST:-}" ]]; then
  log 'MCAV_PUBLIC_HOST must be the public server IP; VJ is disabled for this start.' >&2
  launch_paper
fi
public_host="${MCAV_PUBLIC_HOST:?MCAV_PUBLIC_HOST must be the public server IP}"
http_port="${HTTP_PORT:-8080}"
dj_port="${VJ_SERVER_PORT:-25808}"
metrics_port="${METRICS_PORT:-9001}"
entity_count="${ENTITY_COUNT:-160}"

if [[ "$http_port" == "$dj_port" ]]; then
  log "HTTP_PORT and VJ_SERVER_PORT must differ (both are $http_port); VJ is disabled for this start." >&2
  launch_paper
fi

endpoint_host="$public_host"
if [[ "$public_host" == *:* ]]; then
  endpoint_host="[$public_host]"
fi
public_origin="https://${endpoint_host}:${http_port}"

web_arguments=()
case "${UNIFIED_WEB:-true}" in
  1|true|TRUE|yes|YES)
    web_arguments=(--unified-web --public-origin "$public_origin")
    ;;
  0|false|FALSE|no|NO)
    broadcast_port="${BROADCAST_PORT:-8766}"
    web_arguments=(--broadcast-port "$broadcast_port")
    ;;
  *)
    log 'UNIFIED_WEB must be true or false; VJ is disabled for this start.' >&2
    launch_paper
    ;;
esac

architecture="${MCAV_ARCH_OVERRIDE:-$(uname -m)}"
case "$architecture" in
  x86_64|amd64)
    runtime="$MCAV_ROOT/bin/linux-amd64/audioviz-vj"
    ;;
  aarch64|arm64)
    runtime="$MCAV_ROOT/bin/linux-arm64/audioviz-vj"
    ;;
  *)
    log "Unsupported architecture '$architecture'; VJ is disabled for this start." >&2
    launch_paper
    ;;
esac

if [[ ! -x "$runtime" ]]; then
  log "Bundled VJ runtime is missing or not executable: $runtime" >&2
  launch_paper
fi

plugins_dir="${MCAV_PLUGINS_DIR:-$(cd "$MCAV_ROOT/.." && pwd)/plugins}"
release_version="$(tr -d '\r\n' < "$MCAV_ROOT/VERSION" 2>/dev/null || printf unknown)"

log 'Checking credentials, TLS identity, plugin, and renderer configuration.'
if ! "$runtime" \
  --bootstrap-pterodactyl \
  --project-root "$MCAV_ROOT" \
  --plugins-dir "$plugins_dir" \
  --release-version "$release_version" \
  --public-host "$public_host" \
  --http-port "$http_port" \
  --port "$dj_port" \
  "${web_arguments[@]}"; then
  log 'Bootstrap failed; VJ is disabled for this start. Paper will still launch.' >&2
  launch_paper
fi

runtime_env="$MCAV_ROOT/state/runtime.env"
shared_secret=''
while IFS='=' read -r key value; do
  if [[ "$key" == 'MINECRAFT_WS_SECRET' ]]; then
    shared_secret="$value"
    break
  fi
done < "$runtime_env"
if [[ -z "$shared_secret" ]]; then
  log "Shared secret is missing from $runtime_env; VJ is disabled for this start." >&2
  launch_paper
fi

log 'Starting authenticated HTTPS/WSS VJ service.'
MINECRAFT_WS_SECRET="$shared_secret" "$runtime" \
  --project-root "$MCAV_ROOT" \
  --minecraft-host 127.0.0.1 \
  --minecraft-port 8765 \
  --auth-file "$MCAV_ROOT/state/dj_auth.json" \
  --http-host 0.0.0.0 \
  --http-port "$http_port" \
  --port "$dj_port" \
  "${web_arguments[@]}" \
  --metrics-port "$metrics_port" \
  --tls-cert "$MCAV_ROOT/state/tls.crt" \
  --tls-key "$MCAV_ROOT/state/tls.key" \
  --entities "$entity_count" \
  --no-spectrograph &
vj_pid=$!

sleep "${MCAV_STARTUP_WAIT_SECONDS:-2}"
if ! kill -0 "$vj_pid" 2>/dev/null; then
  wait "$vj_pid"
  exit_code=$?
  log "VJ exited during startup (status $exit_code); Paper will continue without it." >&2
else
  log "Admin:   ${public_origin}/"
  log "Preview: ${public_origin}/preview/"
  log "DJ:      wss://${endpoint_host}:${dj_port}"
  log "Login:   $MCAV_ROOT/FIRST_LOGIN.txt"
fi

launch_paper
