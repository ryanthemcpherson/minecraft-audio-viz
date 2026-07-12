#!/usr/bin/env bash
set -euo pipefail

# Strict VJ deploy for dev host:
# - force runtime files to origin/main (even in a dirty repo)
# - restart VJ server in a known tmux session
# - verify process + ports

PROJECT_DIR="${PROJECT_DIR:-/home/ryan/minecraft-audio-viz}"
TMUX_SESSION="${TMUX_SESSION:-mcav_vj2}"
MC_HOST="${MC_HOST:-127.0.0.1}"
MC_PORT="${MC_PORT:-18765}"
DJ_PORT="${DJ_PORT:-9000}"
BROADCAST_PORT="${BROADCAST_PORT:-8766}"
METRICS_PORT="${METRICS_PORT:-9001}"

cd "$PROJECT_DIR"

if ! .venv/bin/python - "$MC_HOST" <<'PY'
import ipaddress
import sys

host = sys.argv[1].strip()
if host.startswith("[") and host.endswith("]"):
    host = host[1:-1]
try:
    is_loopback = ipaddress.ip_address(host).is_loopback
except ValueError:
    is_loopback = host.rstrip(".").casefold() == "localhost"
raise SystemExit(0 if is_loopback else 1)
PY
then
  echo "ERROR: MC_HOST must be an explicit loopback endpoint; use a supervised encrypted tunnel" >&2
  exit 1
fi

if ! MCAV_TUNNEL_HOST="$MC_HOST" MCAV_TUNNEL_PORT="$MC_PORT" \
  timeout 2 bash -c 'exec 3<>"/dev/tcp/${MCAV_TUNNEL_HOST}/${MCAV_TUNNEL_PORT}"'; then
  echo "ERROR: no Minecraft renderer or encrypted tunnel is reachable at ${MC_HOST}:${MC_PORT}" >&2
  echo "Start a supervised tunnel, for example: ssh -N -L ${MC_PORT}:127.0.0.1:8765 operator@minecraft-host" >&2
  exit 1
fi

echo "=== VJ Dev Deploy ==="
echo "time: $(date -Iseconds)"
echo "project: $PROJECT_DIR"
echo "session: $TMUX_SESSION"

echo ">> Fetching origin/main..."
git fetch origin main
TARGET_REV="$(git rev-parse --short origin/main)"
echo ">> Target rev: $TARGET_REV"

RUNTIME_PATHS=(
  "vj_server/cli.py"
  "vj_server/vj_server.py"
  "vj_server/patterns.py"
  "vj_server/spectrograph.py"
  "vj_server/viz_client.py"
  "patterns/*.lua"
)

echo ">> Syncing runtime files from origin/main..."
for path in "${RUNTIME_PATHS[@]}"; do
  # shellcheck disable=SC2086
  git checkout origin/main -- $path
done

if git diff --name-only HEAD origin/main -- requirements.txt pyproject.toml vj_server/pyproject.toml | grep -q .; then
  echo ">> Dependency definition changed; syncing venv..."
  .venv/bin/python -m pip install -e . --quiet
fi

echo ">> Restarting VJ server..."
tmux kill-session -t "$TMUX_SESSION" 2>/dev/null || true
tmux new-session -d -s "$TMUX_SESSION" \
  "cd '$PROJECT_DIR' && exec .venv/bin/python -m vj_server.cli --no-auth --port '$DJ_PORT' --broadcast-port '$BROADCAST_PORT' --minecraft-host '$MC_HOST' --minecraft-port '$MC_PORT' --metrics-port '$METRICS_PORT'"

VJ_PID="$(tmux display-message -p -t "$TMUX_SESSION":0.0 '#{pane_pid}' 2>/dev/null || true)"
if [[ ! "$VJ_PID" =~ ^[0-9]+$ ]]; then
  echo "ERROR: could not identify the VJ process in tmux session ${TMUX_SESSION}" >&2
  exit 1
fi

sleep 2

echo ">> Health checks..."
listener_owned_by_vj() {
  ss -ltnp "sport = :$1" | grep -Fq "pid=${VJ_PID},"
}

listeners_ready=false
for _ in {1..20}; do
  if tmux has-session -t "$TMUX_SESSION" 2>/dev/null \
    && kill -0 "$VJ_PID" 2>/dev/null \
    && listener_owned_by_vj "$DJ_PORT" \
    && listener_owned_by_vj "$BROADCAST_PORT" \
    && listener_owned_by_vj "$METRICS_PORT"; then
    listeners_ready=true
    break
  fi
  sleep 0.5
done

if [ "$listeners_ready" != true ]; then
  echo "ERROR: the new VJ process did not stay alive and own every expected listener" >&2
  tmux capture-pane -pt "$TMUX_SESSION" -S -120 | tail -n 40 >&2 || true
  exit 1
fi

renderer_connected=false
for _ in {1..20}; do
  if ! tmux has-session -t "$TMUX_SESSION" 2>/dev/null || ! kill -0 "$VJ_PID" 2>/dev/null; then
    break
  fi
  health_json="$(curl --fail --silent --max-time 2 "http://127.0.0.1:${METRICS_PORT}/health" || true)"
  if printf '%s' "$health_json" | .venv/bin/python -c \
    'import json, sys; raise SystemExit(0 if json.load(sys.stdin).get("minecraft_connected") is True else 1)' \
    2>/dev/null; then
    renderer_connected=true
    break
  fi
  sleep 0.5
done

if [ "$renderer_connected" != true ]; then
  echo "ERROR: VJ server did not authenticate with the Minecraft renderer" >&2
  tmux capture-pane -pt "$TMUX_SESSION" -S -120 | tail -n 40 >&2 || true
  exit 1
fi

echo ">> Active process:"
ps -p "$VJ_PID" -o pid=,args=

echo ">> Listening ports:"
ss -ltnp | grep -F "pid=${VJ_PID},"

echo ">> Last startup logs (tmux capture):"
tmux capture-pane -pt "$TMUX_SESSION" -S -120 | tail -n 40 || true

echo "=== Deploy OK (${TARGET_REV}) ==="
