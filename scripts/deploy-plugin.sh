#!/usr/bin/env bash
# AudioViz - Full Deploy: Plugin JAR + Patterns + VJ Server + Frontends
#
# Syncs everything needed to the remote server, hot-reloads the Minecraft
# plugin, and restarts the VJ server only when its Python code changed.
#
# Usage:
#   ./scripts/deploy-plugin.sh                      # Build + deploy everything
#   ./scripts/deploy-plugin.sh --skip-build         # Deploy without rebuilding JAR
#   ./scripts/deploy-plugin.sh --skip-tests         # Build without tests
#   ./scripts/deploy-plugin.sh --restart            # Full MC server restart
#   ./scripts/deploy-plugin.sh --plugin-only        # JAR + reload only (no sync)
#   ./scripts/deploy-plugin.sh --server 10.0.0.5    # Custom server address

set -euo pipefail

# Defaults
SERVER="192.168.1.204"
USER="ryan"
PLUGINS_DIR="/home/ryan/minecraft-server/plugins"
REMOTE_PROJECT="/home/ryan/minecraft-audio-viz"
RCON_PORT="25575"
RCON_PASS="${MCAV_RCON_PASSWORD:-}"
SKIP_BUILD=false
SKIP_TESTS=false
DO_RESTART=false
PLUGIN_ONLY=false

# Parse args
while [[ $# -gt 0 ]]; do
    case $1 in
        --server)      SERVER="$2"; shift 2 ;;
        --user)        USER="$2"; shift 2 ;;
        --skip-build)  SKIP_BUILD=true; shift ;;
        --skip-tests)  SKIP_TESTS=true; shift ;;
        --restart)     DO_RESTART=true; shift ;;
        --plugin-only) PLUGIN_ONLY=true; shift ;;
        --reload)      shift ;;  # no-op, reload is default
        --rcon-pass)   RCON_PASS="$2"; shift 2 ;;
        --rcon-port)   RCON_PORT="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --server HOST    Server address (default: 192.168.1.204)"
            echo "  --user USER      SSH user (default: ryan)"
            echo "  --skip-build     Deploy existing JAR without rebuilding"
            echo "  --skip-tests     Build without running tests"
            echo "  --restart        Full MC server restart (default: hot-reload)"
            echo "  --plugin-only    Only deploy JAR + reload (skip pattern/VJ sync)"
            echo "  --rcon-pass PASS RCON password (or set MCAV_RCON_PASSWORD)"
            echo "  --rcon-port PORT RCON port (default: 25575)"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PLUGIN_DIR="$PROJECT_ROOT/minecraft_plugin"
SSH_TARGET="${USER}@${SERVER}"

TOTAL_STEPS=4
if [ "$PLUGIN_ONLY" = true ]; then TOTAL_STEPS=3; fi

echo ""
echo "  AudioViz Deploy"
echo "  ==============="
echo "  Server:  $SSH_TARGET"
echo "  Mode:    $([ "$PLUGIN_ONLY" = true ] && echo "plugin-only" || echo "full sync")"
echo ""

# Auto-detect RCON password from server if not set
if [ -z "$RCON_PASS" ]; then
    RCON_PASS=$(ssh "$SSH_TARGET" "grep '^rcon.password=' /home/ryan/minecraft-server/server.properties 2>/dev/null | cut -d= -f2" 2>/dev/null) || true
fi
if [ -z "$RCON_PASS" ]; then
    echo "  ERROR: RCON password not found. Set MCAV_RCON_PASSWORD or pass --rcon-pass." >&2
    exit 1
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Build
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/$TOTAL_STEPS] Building plugin JAR..."

    MVN_ARGS="package"
    if [ "$SKIP_TESTS" = true ]; then
        MVN_ARGS="package -DskipTests"
    fi

    cd "$PLUGIN_DIR"

    if [ -f "./mvnw" ]; then
        MVN_CMD="./mvnw"
    elif [ -f "./mvnw.cmd" ]; then
        MVN_CMD="./mvnw.cmd"
    elif command -v mvn &>/dev/null; then
        MVN_CMD="mvn"
    else
        echo "  ERROR: Maven not found" >&2
        exit 1
    fi

    # Set JAVA_HOME if not set and JDK exists at known path
    if [ -z "${JAVA_HOME:-}" ]; then
        for jdk in "/c/Program Files/Eclipse Adoptium/jdk-"*/; do
            if [ -d "$jdk" ]; then
                export JAVA_HOME="$jdk"
                break
            fi
        done
    fi

    echo "  Running: $MVN_CMD $MVN_ARGS"
    $MVN_CMD $MVN_ARGS 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|ERROR" || true

    cd "$PROJECT_ROOT"

    JAR_FILE=$(ls -t "$PLUGIN_DIR/target"/audioviz-plugin-*-SNAPSHOT.jar 2>/dev/null | grep -v original | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "  ERROR: No JAR file found after build" >&2
        exit 1
    fi

    JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
    echo "  Built: $(basename "$JAR_FILE") ($JAR_SIZE)"
else
    echo "[1/$TOTAL_STEPS] Skipping build"

    JAR_FILE=$(ls -t "$PLUGIN_DIR/target"/audioviz-plugin-*-SNAPSHOT.jar 2>/dev/null | grep -v original | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "  ERROR: No existing JAR found. Run without --skip-build." >&2
        exit 1
    fi
    echo "  Using: $(basename "$JAR_FILE")"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Sync files to server
# ─────────────────────────────────────────────────────────────────────────────
echo "[2/$TOTAL_STEPS] Deploying to $SERVER..."

# Always deploy the plugin JAR
ssh "$SSH_TARGET" "mkdir -p '${PLUGINS_DIR}'"
scp -q "$JAR_FILE" "${SSH_TARGET}:${PLUGINS_DIR}/"
echo "  Plugin JAR deployed"

if [ "$PLUGIN_ONLY" = false ]; then

    # Snapshot VJ server hashes before sync to detect changes
    VJ_HASH_BEFORE=$(ssh "$SSH_TARGET" "md5sum '${REMOTE_PROJECT}/vj_server/vj_server.py' '${REMOTE_PROJECT}/vj_server/patterns.py' '${REMOTE_PROJECT}/vj_server/config.py' '${REMOTE_PROJECT}/vj_server/cli.py' 2>/dev/null | sort" 2>/dev/null) || true

    # --- Patterns (Lua) ---
    scp -q "$PROJECT_ROOT"/patterns/*.lua "${SSH_TARGET}:${REMOTE_PROJECT}/patterns/"
    echo "  Patterns synced ($(ls -1 "$PROJECT_ROOT"/patterns/*.lua | wc -l) files)"

    # --- VJ Server (Python) ---
    scp -q "$PROJECT_ROOT"/vj_server/*.py "${SSH_TARGET}:${REMOTE_PROJECT}/vj_server/"
    echo "  VJ server synced"

    # --- Admin Panel ---
    scp -q "$PROJECT_ROOT"/admin_panel/index.html "${SSH_TARGET}:${REMOTE_PROJECT}/admin_panel/"
    scp -q "$PROJECT_ROOT"/admin_panel/js/*.js "${SSH_TARGET}:${REMOTE_PROJECT}/admin_panel/js/"
    scp -q "$PROJECT_ROOT"/admin_panel/css/*.css "${SSH_TARGET}:${REMOTE_PROJECT}/admin_panel/css/"
    echo "  Admin panel synced"

    # --- Preview Tool ---
    scp -q "$PROJECT_ROOT"/preview_tool/frontend/index.html "${SSH_TARGET}:${REMOTE_PROJECT}/preview_tool/frontend/"
    scp -q "$PROJECT_ROOT"/preview_tool/frontend/js/*.js "${SSH_TARGET}:${REMOTE_PROJECT}/preview_tool/frontend/js/"
    scp -q "$PROJECT_ROOT"/preview_tool/frontend/css/*.css "${SSH_TARGET}:${REMOTE_PROJECT}/preview_tool/frontend/css/"
    echo "  Preview tool synced"

    # Check if VJ server Python code changed
    VJ_HASH_AFTER=$(ssh "$SSH_TARGET" "md5sum '${REMOTE_PROJECT}/vj_server/vj_server.py' '${REMOTE_PROJECT}/vj_server/patterns.py' '${REMOTE_PROJECT}/vj_server/config.py' '${REMOTE_PROJECT}/vj_server/cli.py' 2>/dev/null | sort" 2>/dev/null) || true

    if [ "$VJ_HASH_BEFORE" != "$VJ_HASH_AFTER" ]; then
        VJ_CHANGED=true
        echo "  VJ server code changed - will restart"
    else
        VJ_CHANGED=false
        echo "  VJ server code unchanged - patterns will hot-reload"
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Hot-reload or Restart Minecraft plugin
# ─────────────────────────────────────────────────────────────────────────────
if [ "$DO_RESTART" = true ]; then
    echo "[3/$TOTAL_STEPS] Restarting Minecraft server..."
    ssh "$SSH_TARGET" "sudo systemctl restart minecraft.service"

    echo "  Waiting for server..."
    sleep 15

    STATUS=$(ssh "$SSH_TARGET" "systemctl is-active minecraft.service" 2>&1 || true)
    if echo "$STATUS" | grep -q "active"; then
        echo "  Server is running"
    else
        echo "  WARNING: Server may not have started. Check logs."
    fi
else
    echo "[3/$TOTAL_STEPS] Hot-reloading plugin..."

    RELOAD_OUTPUT=$(ssh "$SSH_TARGET" \
        "mcrcon -H 127.0.0.1 -P ${RCON_PORT} -p '${RCON_PASS}' 'plugman reload audioviz'" 2>&1) || true

    if echo "$RELOAD_OUTPUT" | grep -qi "reload"; then
        echo "  Plugin reloaded"
    else
        echo "  Reload response: $RELOAD_OUTPUT"
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Restart VJ server if needed
# ─────────────────────────────────────────────────────────────────────────────
if [ "$PLUGIN_ONLY" = false ]; then
    echo "[4/$TOTAL_STEPS] VJ server..."

    if [ "${VJ_CHANGED:-false}" = true ]; then
        echo "  Restarting VJ server (Python code changed)..."

        # Find and kill existing VJ server
        VJ_PID=$(ssh "$SSH_TARGET" "ps aux | grep 'vj_server.cli' | grep -v grep | grep python | awk '{print \$2}'" 2>/dev/null) || true

        if [ -n "$VJ_PID" ]; then
            ssh "$SSH_TARGET" "kill $VJ_PID" 2>/dev/null || true
            sleep 2
        fi

        # Start fresh VJ server in background
        ssh "$SSH_TARGET" "cd '${REMOTE_PROJECT}' && nohup .venv/bin/python -m vj_server.cli --no-auth --minecraft-host ${SERVER} > /tmp/vj_server.log 2>&1 &"
        sleep 3

        # Verify it started
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

# ─────────────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "  Deploy complete!"
echo "  WebSocket:     ws://${SERVER}:8765"
echo "  Admin Panel:   http://${SERVER}:8081"
echo "  Preview:       http://${SERVER}:8080"
echo "  VJ Server:     ws://${SERVER}:9000"
echo ""
