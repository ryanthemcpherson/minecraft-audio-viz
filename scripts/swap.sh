#!/usr/bin/env bash
# Swap between Paper plugin server and Fabric mod server
# Usage: ./swap.sh paper|fabric|status
#
# Place this on the Minecraft server at ~/minecraft-server/swap.sh

set -euo pipefail

SERVER_DIR="/home/ryan/minecraft-server"
SERVICE="minecraft.service"
SERVICE_FILE="/etc/systemd/system/${SERVICE}"

PAPER_JAR="paper.jar"
FABRIC_JAR="fabric-server-launch.jar"

current_mode() {
    local jar
    jar=$(grep -oP '(?<=-jar )\S+' "$SERVICE_FILE" 2>/dev/null || echo "")
    case "$jar" in
        *paper*)  echo "paper" ;;
        *fabric*) echo "fabric" ;;
        *)        echo "unknown" ;;
    esac
}

show_status() {
    local mode active
    mode=$(current_mode)
    active=$(systemctl is-active "$SERVICE" 2>/dev/null || echo "unknown")
    echo ""
    echo "  Minecraft Server Status"
    echo "  ========================"
    echo "  Mode:    $mode"
    echo "  Status:  $active"
    echo "  Plugin:  $(ls -lh ${SERVER_DIR}/plugins/audioviz-plugin-*.jar 2>/dev/null | awk '{print $5, $6, $7, $8}' || echo 'not found')"
    echo "  Mod:     $(ls -lh ${SERVER_DIR}/mods/audioviz-mod-*.jar 2>/dev/null | awk '{print $5, $6, $7, $8}' || echo 'not found')"
    echo ""
}

swap_to() {
    local target="$1"
    local current
    current=$(current_mode)

    if [ "$current" = "$target" ]; then
        echo "Already running $target."
        show_status
        return 0
    fi

    local jar desc
    case "$target" in
        paper)
            jar="$PAPER_JAR"
            desc="Minecraft Paper Server"
            ;;
        fabric)
            jar="$FABRIC_JAR"
            desc="Minecraft Fabric Server"
            ;;
    esac

    if [ ! -f "${SERVER_DIR}/${jar}" ]; then
        echo "ERROR: ${jar} not found in ${SERVER_DIR}" >&2
        exit 1
    fi

    echo "Swapping: $current -> $target"
    echo "Stopping server..."
    sudo systemctl stop "$SERVICE" 2>/dev/null || true
    sleep 3

    echo "Updating service to use ${jar}..."
    sudo bash -c "cat > $SERVICE_FILE" << EOF
[Unit]
Description=${desc}
After=network.target

[Service]
Type=simple
User=ryan
WorkingDirectory=${SERVER_DIR}
ExecStart=/usr/bin/java -Xms4G -Xmx4G -jar ${jar} nogui
ExecStop=/bin/kill -TERM \$MAINPID
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

    sudo systemctl daemon-reload
    echo "Starting $target server..."
    sudo systemctl start "$SERVICE"

    echo "Waiting for server to start..."
    sleep 12

    local status
    status=$(systemctl is-active "$SERVICE" 2>/dev/null || echo "failed")
    if [ "$status" = "active" ]; then
        echo "Server is running ($target)"
    else
        echo "WARNING: Server may not have started. Check: journalctl -u $SERVICE -n 50"
    fi

    show_status
}

case "${1:-}" in
    paper)  swap_to paper ;;
    fabric) swap_to fabric ;;
    status) show_status ;;
    *)
        echo "Usage: $0 paper|fabric|status"
        echo ""
        echo "  paper   - Switch to Paper server (uses plugins/)"
        echo "  fabric  - Switch to Fabric server (uses mods/)"
        echo "  status  - Show current server mode"
        exit 1
        ;;
esac
