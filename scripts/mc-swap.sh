#!/usr/bin/env bash
# Swap between Paper and Fabric on the dev server (runs remotely via SSH)
# Usage: ./scripts/mc-swap.sh paper|fabric|status

set -euo pipefail

SERVER="${MCAV_SERVER:-192.168.1.204}"
USER="${MCAV_USER:-ryan}"
SSH_TARGET="${USER}@${SERVER}"

case "${1:-}" in
    paper|fabric|status)
        ssh "$SSH_TARGET" "bash /home/ryan/minecraft-server/swap.sh $1"
        ;;
    *)
        echo "Usage: $0 paper|fabric|status"
        echo ""
        echo "  paper   - Switch to Paper server (uses plugins/)"
        echo "  fabric  - Switch to Fabric server (uses mods/)"
        echo "  status  - Show current server mode and JAR versions"
        exit 1
        ;;
esac
