#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/home/ryan/minecraft-audio-viz"
cd "$PROJECT_DIR"

echo "=== MCAV Deploy ==="
echo "$(date)"

# Pull latest
echo ">> Pulling latest from origin/main..."
git fetch origin main
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)

if [ "$LOCAL" = "$REMOTE" ]; then
    echo ">> Already up to date ($LOCAL)"
    if [ "${1:-}" != "--force" ]; then
        exit 0
    fi
    echo ">> --force flag set, redeploying anyway"
fi

git reset --hard origin/main
echo ">> Updated to $(git rev-parse --short HEAD): $(git log -1 --format=%s)"

# Reinstall Python package if pyproject.toml changed
if git diff --name-only "$LOCAL" "$REMOTE" 2>/dev/null | grep -q "pyproject.toml"; then
    echo ">> pyproject.toml changed, reinstalling..."
    source .venv/bin/activate
    pip install -e . --quiet
fi

# Helper: restart an HTTP server in a tmux session
restart_http() {
    local session="$1" dir="$2" port="$3"
    if tmux has-session -t "$session" 2>/dev/null; then
        tmux send-keys -t "$session" C-c
        sleep 1
        tmux send-keys -t "$session" "cd $dir && python3 -m http.server $port --bind 0.0.0.0" Enter
    else
        tmux new-session -d -s "$session" "cd $dir && python3 -m http.server $port --bind 0.0.0.0"
    fi
}

# Restart HTTP servers if frontend files changed
if git diff --name-only "$LOCAL" "$REMOTE" 2>/dev/null | grep -q "^admin_panel/" || [ "${1:-}" = "--force" ]; then
    echo ">> Restarting admin panel (port 8081)..."
    restart_http admin "$PROJECT_DIR/admin_panel" 8081
fi

if git diff --name-only "$LOCAL" "$REMOTE" 2>/dev/null | grep -q "^preview_tool/" || [ "${1:-}" = "--force" ]; then
    echo ">> Restarting preview server (port 8080)..."
    restart_http preview "$PROJECT_DIR/preview_tool/frontend" 8080
fi

# Restart VJ server if vj_server changed
if git diff --name-only "$LOCAL" "$REMOTE" 2>/dev/null | grep -q "^vj_server/" || [ "${1:-}" = "--force" ]; then
    echo ">> Restarting VJ server..."
    pkill -f "audioviz-vj" 2>/dev/null || true
    sleep 2
    source .venv/bin/activate
    nohup audioviz-vj --host 0.0.0.0 > vj_server.log 2>&1 &
    echo ">> VJ server PID: $!"
fi

echo "=== Deploy complete ==="
