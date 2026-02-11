#!/usr/bin/env bash
# AudioViz - Build & Deploy Plugin to Minecraft Server
#
# Default behavior: Build, deploy, hot-reload via PlugManX (no restart needed)
#
# Usage:
#   ./scripts/deploy-plugin.sh                      # Build, deploy, hot-reload
#   ./scripts/deploy-plugin.sh --skip-build         # Deploy existing JAR only
#   ./scripts/deploy-plugin.sh --skip-tests         # Build without tests
#   ./scripts/deploy-plugin.sh --restart            # Full server restart instead of hot-reload
#   ./scripts/deploy-plugin.sh --server 10.0.0.5    # Custom server

set -euo pipefail

# Defaults
SERVER="192.168.1.204"
USER="ryan"
PLUGINS_DIR="/home/ryan/minecraft-server/plugins"
SERVER_DIR="/home/ryan/minecraft-server"
RCON_PORT="25575"
RCON_PASS="${MCAV_RCON_PASSWORD:-}"
SKIP_BUILD=false
SKIP_TESTS=false
DO_RESTART=false

# Parse args
while [[ $# -gt 0 ]]; do
    case $1 in
        --server)    SERVER="$2"; shift 2 ;;
        --user)      USER="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --skip-tests) SKIP_TESTS=true; shift ;;
        --restart)   DO_RESTART=true; shift ;;
        --reload)    shift ;;  # no-op, reload is now default
        --rcon-pass) RCON_PASS="$2"; shift 2 ;;
        --rcon-port) RCON_PORT="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--server HOST] [--skip-build] [--skip-tests] [--restart]"
            echo ""
            echo "Options:"
            echo "  --server HOST    Minecraft server address (default: 192.168.1.204)"
            echo "  --user USER      SSH user (default: ryan)"
            echo "  --skip-build     Deploy existing JAR without rebuilding"
            echo "  --skip-tests     Build without running tests"
            echo "  --restart        Full server restart (default: hot-reload via PlugManX)"
            echo "  --rcon-pass PASS RCON password (default: from server.properties)"
            echo "  --rcon-port PORT RCON port (default: 25575)"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PLUGIN_DIR="$PROJECT_ROOT/minecraft_plugin"

echo ""
echo "  AudioViz Plugin Deploy"
echo "  ======================"
echo "  Server:  $USER@$SERVER"
echo "  Plugins: $PLUGINS_DIR"
echo ""

if [ -z "$RCON_PASS" ]; then
    echo "  ERROR: RCON password required. Set MCAV_RCON_PASSWORD or pass --rcon-pass." >&2
    exit 1
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Build
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/3] Building plugin JAR..."

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
    echo "[1/3] Skipping build"

    JAR_FILE=$(ls -t "$PLUGIN_DIR/target"/audioviz-plugin-*-SNAPSHOT.jar 2>/dev/null | grep -v original | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "  ERROR: No existing JAR found. Run without --skip-build." >&2
        exit 1
    fi
    echo "  Using: $(basename "$JAR_FILE")"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Deploy JAR
# ─────────────────────────────────────────────────────────────────────────────
echo "[2/3] Deploying to $SERVER..."

ssh "${USER}@${SERVER}" "mkdir -p '${PLUGINS_DIR}' && rm -f '${PLUGINS_DIR}'/audioviz-plugin-*.jar"
scp "$JAR_FILE" "${USER}@${SERVER}:${PLUGINS_DIR}/"
echo "  Deployed successfully"

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Hot-reload or Restart
# ─────────────────────────────────────────────────────────────────────────────
if [ "$DO_RESTART" = true ]; then
    echo "[3/3] Restarting Minecraft server..."
    ssh "${USER}@${SERVER}" "sudo systemctl restart minecraft.service"

    echo "  Waiting for server..."
    sleep 15

    STATUS=$(ssh "${USER}@${SERVER}" "systemctl is-active minecraft.service" 2>&1 || true)
    if echo "$STATUS" | grep -q "active"; then
        echo "  Server is running"
    else
        echo "  WARNING: Server may not have started. Check logs."
    fi
else
    echo "[3/3] Hot-reloading plugin via PlugManX..."

    # Use mcrcon to send PlugManX reload command (no server restart needed)
    RELOAD_OUTPUT=$(ssh "${USER}@${SERVER}" \
        "mcrcon -H 127.0.0.1 -P ${RCON_PORT} -p '${RCON_PASS}' 'plugman reload audioviz'" 2>&1) || true

    if echo "$RELOAD_OUTPUT" | grep -qi "success\|reload\|unload"; then
        echo "  Hot-reload successful"
        echo "  $RELOAD_OUTPUT" | head -5
    else
        echo "  Hot-reload response: $RELOAD_OUTPUT"
        echo ""
        echo "  If reload failed, try: $0 --restart"
    fi

    # Quick health check via RCON
    PLUGIN_LIST=$(ssh "${USER}@${SERVER}" \
        "mcrcon -H 127.0.0.1 -P ${RCON_PORT} -p '${RCON_PASS}' 'plugman info audioviz'" 2>&1) || true

    if echo "$PLUGIN_LIST" | grep -qi "enabled"; then
        echo "  AudioViz plugin is enabled"
    fi
fi

echo ""
echo "  Deploy complete!"
echo "  WebSocket: ws://${SERVER}:8765"
echo ""
