#!/usr/bin/env bash
set -euo pipefail

# Strict VJ deploy for dev host:
# - force runtime files to origin/main (even in a dirty repo)
# - restart VJ server in a known tmux session
# - verify process + ports

PROJECT_DIR="${PROJECT_DIR:-/home/ryan/minecraft-audio-viz}"
TMUX_SESSION="${TMUX_SESSION:-mcav_vj2}"
MC_HOST="${MC_HOST:-192.168.1.204}"
DJ_PORT="${DJ_PORT:-9000}"
BROADCAST_PORT="${BROADCAST_PORT:-8766}"

cd "$PROJECT_DIR"

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
  "python_client/viz_client.py"
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
  "cd '$PROJECT_DIR' && .venv/bin/python -m vj_server.cli --no-auth --minecraft-host '$MC_HOST'"

sleep 2

echo ">> Health checks..."
if ! pgrep -af "python -m vj_server.cli" >/dev/null; then
  echo "ERROR: VJ server process not found after restart" >&2
  exit 1
fi

if ! ss -ltnp | grep -q ":${DJ_PORT}"; then
  echo "ERROR: DJ port ${DJ_PORT} is not listening" >&2
  exit 1
fi

if ! ss -ltnp | grep -q ":${BROADCAST_PORT}"; then
  echo "ERROR: Broadcast port ${BROADCAST_PORT} is not listening" >&2
  exit 1
fi

echo ">> Active process:"
pgrep -af "python -m vj_server.cli"

echo ">> Listening ports:"
ss -ltnp | grep -E ":${DJ_PORT}|:${BROADCAST_PORT}"

echo ">> Last startup logs (tmux capture):"
tmux capture-pane -pt "$TMUX_SESSION" -S -120 | tail -n 40 || true

echo "=== Deploy OK (${TARGET_REV}) ==="
