#!/usr/bin/env bash
# Start Minecraft server with optimized JVM flags for AudioViz.
#
# Usage:
#   bash scripts/start-minecraft-server.sh                    # default: paper.jar
#   bash scripts/start-minecraft-server.sh fabric-server.jar  # custom jar
#
# Expects to be run from the Minecraft server directory (where the jar lives).
# Requires Java 21+.

set -euo pipefail

JAR="${1:-paper.jar}"

if [[ ! -f "$JAR" ]]; then
    echo "Error: $JAR not found in $(pwd)"
    echo "Usage: bash $0 [server.jar]"
    exit 1
fi

# Verify Java 21+ for ZGC generational support
JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [[ "$JAVA_VER" -lt 21 ]]; then
    echo "Error: Java 21+ required (found Java $JAVA_VER). ZGC generational requires Java 21."
    exit 1
fi

exec java \
    -Xms4G -Xmx4G \
    -XX:+UseZGC -XX:+ZGenerational \
    -XX:+AlwaysPreTouch \
    -XX:+UseStringDeduplication \
    -jar "$JAR" nogui
