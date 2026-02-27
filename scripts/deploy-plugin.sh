#!/usr/bin/env bash
# AudioViz - Full Deploy: Mod JAR + Patterns + VJ Server + Frontends
#
# Syncs everything needed to the remote server and restarts services.
# Fabric mods require a full server restart (no hot-reload).
#
# Usage:
#   ./scripts/deploy-plugin.sh                      # Build + deploy everything
#   ./scripts/deploy-plugin.sh --skip-build         # Deploy without rebuilding JAR
#   ./scripts/deploy-plugin.sh --skip-tests         # Build without tests
#   ./scripts/deploy-plugin.sh --mod-only           # JAR + restart only (no sync)
#   ./scripts/deploy-plugin.sh --server 10.0.0.5    # Custom server address

set -euo pipefail

# Defaults
SERVER="192.168.1.204"
USER="ryan"
MODS_DIR="/home/ryan/minecraft-server/mods"
REMOTE_PROJECT="/home/ryan/minecraft-audio-viz"
SKIP_BUILD=false
SKIP_TESTS=false
MOD_ONLY=false

# Parse args
while [[ $# -gt 0 ]]; do
    case $1 in
        --server)      SERVER="$2"; shift 2 ;;
        --user)        USER="$2"; shift 2 ;;
        --skip-build)  SKIP_BUILD=true; shift ;;
        --skip-tests)  SKIP_TESTS=true; shift ;;
        --mod-only)    MOD_ONLY=true; shift ;;
        -h|--help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --server HOST    Server address (default: 192.168.1.204)"
            echo "  --user USER      SSH user (default: ryan)"
            echo "  --skip-build     Deploy existing JAR without rebuilding"
            echo "  --skip-tests     Build without running tests"
            echo "  --mod-only       Only deploy JAR + restart (skip pattern/VJ sync)"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
MOD_DIR="$PROJECT_ROOT/minecraft_mod"
SSH_TARGET="${USER}@${SERVER}"

TOTAL_STEPS=4
if [ "$MOD_ONLY" = true ]; then TOTAL_STEPS=3; fi

echo ""
echo "  AudioViz Deploy (Fabric)"
echo "  ========================"
echo "  Server:  $SSH_TARGET"
echo "  Mode:    $([ "$MOD_ONLY" = true ] && echo "mod-only" || echo "full sync")"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Build
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/$TOTAL_STEPS] Building mod JAR..."

    GRADLE_ARGS="build"
    if [ "$SKIP_TESTS" = true ]; then
        GRADLE_ARGS="build -x test"
    fi

    cd "$MOD_DIR"

    # Set JAVA_HOME if not set and JDK exists at known path
    if [ -z "${JAVA_HOME:-}" ]; then
        for jdk in "/c/Program Files/Eclipse Adoptium/jdk-"*/; do
            if [ -d "$jdk" ]; then
                export JAVA_HOME="$jdk"
                break
            fi
        done
    fi

    echo "  Running: ./gradlew $GRADLE_ARGS"
    ./gradlew $GRADLE_ARGS 2>&1 | grep -E "BUILD (SUCCESSFUL|FAILED)|ERROR" || true

    cd "$PROJECT_ROOT"

    JAR_FILE=$(ls -t "$MOD_DIR/build/libs"/audioviz-mod-*.jar 2>/dev/null | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "  ERROR: No JAR file found after build" >&2
        exit 1
    fi

    JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
    echo "  Built: $(basename "$JAR_FILE") ($JAR_SIZE)"
else
    echo "[1/$TOTAL_STEPS] Skipping build"

    JAR_FILE=$(ls -t "$MOD_DIR/build/libs"/audioviz-mod-*.jar 2>/dev/null | head -1)
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

# Always deploy the mod JAR
ssh "$SSH_TARGET" "mkdir -p '${MODS_DIR}'"
ssh "$SSH_TARGET" "rm -f '${MODS_DIR}'/audioviz-mod-*.jar"
scp -q "$JAR_FILE" "${SSH_TARGET}:${MODS_DIR}/"
echo "  Mod JAR deployed"

if [ "$MOD_ONLY" = false ]; then

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
# Step 3: Restart Minecraft server (Fabric requires full restart)
# ─────────────────────────────────────────────────────────────────────────────
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

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Restart VJ server if needed
# ─────────────────────────────────────────────────────────────────────────────
if [ "$MOD_ONLY" = false ]; then
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
