#!/usr/bin/env bash
# AudioViz - Deploy Paper Plugin JAR + optional full sync
#
# Usage:
#   ./scripts/deploy-paper.sh                      # Build + deploy everything
#   ./scripts/deploy-paper.sh --skip-build         # Deploy without rebuilding JAR
#   ./scripts/deploy-paper.sh --skip-tests         # Build without tests
#   ./scripts/deploy-paper.sh --jar-only           # JAR + restart only (no sync)
#   ./scripts/deploy-paper.sh --server 10.0.0.5    # Custom server address

set -euo pipefail

# Defaults
SERVER="192.168.1.204"
USER="ryan"
PLUGINS_DIR="/home/ryan/minecraft-server/plugins"
REMOTE_PROJECT="/home/ryan/minecraft-audio-viz"
SKIP_BUILD=false
SKIP_TESTS=false
JAR_ONLY=false

# Parse args
while [[ $# -gt 0 ]]; do
    case $1 in
        --server)      SERVER="$2"; shift 2 ;;
        --user)        USER="$2"; shift 2 ;;
        --skip-build)  SKIP_BUILD=true; shift ;;
        --skip-tests)  SKIP_TESTS=true; shift ;;
        --jar-only)    JAR_ONLY=true; shift ;;
        -h|--help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --server HOST    Server address (default: 192.168.1.204)"
            echo "  --user USER      SSH user (default: ryan)"
            echo "  --skip-build     Deploy existing JAR without rebuilding"
            echo "  --skip-tests     Build without running tests"
            echo "  --jar-only       Only deploy JAR + restart (skip pattern/VJ sync)"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PLUGIN_DIR="$PROJECT_ROOT/minecraft_plugin"
SSH_TARGET="${USER}@${SERVER}"

TOTAL_STEPS=4
if [ "$JAR_ONLY" = true ]; then TOTAL_STEPS=3; fi

echo ""
echo "  AudioViz Deploy (Paper)"
echo "  ========================"
echo "  Server:  $SSH_TARGET"
echo "  Mode:    $([ "$JAR_ONLY" = true ] && echo "jar-only" || echo "full sync")"
echo ""

# Step 1: Build
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/$TOTAL_STEPS] Building plugin JAR..."

    MVN_ARGS="package -q"
    if [ "$SKIP_TESTS" = true ]; then
        MVN_ARGS="package -q -DskipTests"
    fi

    cd "$PLUGIN_DIR"
    echo "  Running: ./mvnw $MVN_ARGS"
    ./mvnw $MVN_ARGS 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|ERROR" || true
    cd "$PROJECT_ROOT"

    JAR_FILE=$(ls -t "$PLUGIN_DIR/target"/audioviz-plugin-*-SNAPSHOT.jar 2>/dev/null | grep -v shaded | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "  ERROR: No JAR file found after build" >&2
        exit 1
    fi

    JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
    echo "  Built: $(basename "$JAR_FILE") ($JAR_SIZE)"
else
    echo "[1/$TOTAL_STEPS] Skipping build"

    JAR_FILE=$(ls -t "$PLUGIN_DIR/target"/audioviz-plugin-*-SNAPSHOT.jar 2>/dev/null | grep -v shaded | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "  ERROR: No existing JAR found. Run without --skip-build." >&2
        exit 1
    fi
    echo "  Using: $(basename "$JAR_FILE")"
fi

# Step 2: Deploy
echo "[2/$TOTAL_STEPS] Deploying to $SERVER..."

ssh "$SSH_TARGET" "rm -f '${PLUGINS_DIR}'/audioviz-plugin-*.jar"
scp -q "$JAR_FILE" "${SSH_TARGET}:${PLUGINS_DIR}/"
echo "  Plugin JAR deployed"

if [ "$JAR_ONLY" = false ]; then
    VJ_HASH_BEFORE=$(ssh "$SSH_TARGET" "md5sum '${REMOTE_PROJECT}/vj_server/vj_server.py' '${REMOTE_PROJECT}/vj_server/patterns.py' '${REMOTE_PROJECT}/vj_server/config.py' '${REMOTE_PROJECT}/vj_server/cli.py' 2>/dev/null | sort" 2>/dev/null) || true

    scp -q "$PROJECT_ROOT"/patterns/*.lua "${SSH_TARGET}:${REMOTE_PROJECT}/patterns/"
    echo "  Patterns synced ($(ls -1 "$PROJECT_ROOT"/patterns/*.lua | wc -l) files)"

    scp -q "$PROJECT_ROOT"/vj_server/*.py "${SSH_TARGET}:${REMOTE_PROJECT}/vj_server/"
    echo "  VJ server synced"

    scp -q "$PROJECT_ROOT"/admin_panel/index.html "${SSH_TARGET}:${REMOTE_PROJECT}/admin_panel/"
    scp -q "$PROJECT_ROOT"/admin_panel/js/*.js "${SSH_TARGET}:${REMOTE_PROJECT}/admin_panel/js/"
    scp -q "$PROJECT_ROOT"/admin_panel/css/*.css "${SSH_TARGET}:${REMOTE_PROJECT}/admin_panel/css/"
    echo "  Admin panel synced"

    scp -q "$PROJECT_ROOT"/preview_tool/frontend/index.html "${SSH_TARGET}:${REMOTE_PROJECT}/preview_tool/frontend/"
    scp -q "$PROJECT_ROOT"/preview_tool/frontend/js/*.js "${SSH_TARGET}:${REMOTE_PROJECT}/preview_tool/frontend/js/"
    scp -q "$PROJECT_ROOT"/preview_tool/frontend/css/*.css "${SSH_TARGET}:${REMOTE_PROJECT}/preview_tool/frontend/css/"
    echo "  Preview tool synced"

    VJ_HASH_AFTER=$(ssh "$SSH_TARGET" "md5sum '${REMOTE_PROJECT}/vj_server/vj_server.py' '${REMOTE_PROJECT}/vj_server/patterns.py' '${REMOTE_PROJECT}/vj_server/config.py' '${REMOTE_PROJECT}/vj_server/cli.py' 2>/dev/null | sort" 2>/dev/null) || true

    if [ "$VJ_HASH_BEFORE" != "$VJ_HASH_AFTER" ]; then
        VJ_CHANGED=true
        echo "  VJ server code changed - will restart"
    else
        VJ_CHANGED=false
        echo "  VJ server code unchanged - patterns will hot-reload"
    fi
fi

# Step 3: Swap to Paper and restart
echo "[3/$TOTAL_STEPS] Ensuring Paper server is running..."

CURRENT_MODE=$(ssh "$SSH_TARGET" "grep -oP '(?<=-jar )\S+' /etc/systemd/system/minecraft.service" 2>/dev/null || echo "")
if echo "$CURRENT_MODE" | grep -q "paper"; then
    echo "  Already on Paper, restarting..."
    ssh "$SSH_TARGET" "sudo systemctl restart minecraft.service"
else
    echo "  Swapping to Paper..."
    ssh "$SSH_TARGET" "bash /home/ryan/minecraft-server/swap.sh paper"
fi

echo "  Waiting for server..."
sleep 15

STATUS=$(ssh "$SSH_TARGET" "systemctl is-active minecraft.service" 2>&1 || true)
if echo "$STATUS" | grep -q "active"; then
    echo "  Server is running (Paper)"
else
    echo "  WARNING: Server may not have started. Check logs."
fi

# Step 4: Restart VJ server if needed
if [ "$JAR_ONLY" = false ]; then
    echo "[4/$TOTAL_STEPS] VJ server..."

    if [ "${VJ_CHANGED:-false}" = true ]; then
        echo "  Restarting VJ server (Python code changed)..."

        VJ_PID=$(ssh "$SSH_TARGET" "ps aux | grep 'vj_server.cli' | grep -v grep | grep python | awk '{print \$2}'" 2>/dev/null) || true

        if [ -n "$VJ_PID" ]; then
            ssh "$SSH_TARGET" "kill $VJ_PID" 2>/dev/null || true
            sleep 2
        fi

        ssh "$SSH_TARGET" "cd '${REMOTE_PROJECT}' && nohup .venv/bin/python -m vj_server.cli --no-auth --minecraft-host ${SERVER} > /tmp/vj_server.log 2>&1 &"
        sleep 3

        VJ_CHECK=$(ssh "$SSH_TARGET" "ps aux | grep 'vj_server.cli' | grep -v grep | grep python" 2>/dev/null) || true
        if [ -n "$VJ_CHECK" ]; then
            echo "  VJ server restarted"
        else
            echo "  WARNING: VJ server may not have started. Check /tmp/vj_server.log"
        fi
    else
        echo "  No restart needed (Lua patterns hot-reload automatically)"
    fi
fi

echo ""
echo "  Deploy complete! (Paper)"
echo "  WebSocket:     ws://${SERVER}:8765"
echo "  Admin Panel:   http://${SERVER}:8081"
echo "  Preview:       http://${SERVER}:8080"
echo "  VJ Server:     ws://${SERVER}:9000"
echo ""
