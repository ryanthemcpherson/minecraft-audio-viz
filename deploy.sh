#!/usr/bin/env bash
# deploy.sh - Deploy minecraft-audio-viz to the dev server
#
# Usage:
#   ./deploy.sh              # Full deploy (pull, build, install, restart)
#   ./deploy.sh --skip-build # Skip Maven build (use existing JAR)
#   ./deploy.sh --skip-tests # Skip Python tests
#   ./deploy.sh --dry-run    # Show what would happen without doing it
#
# Environment variables:
#   PLUGINS_DIR    - Minecraft plugins directory (default: ~/minecraft-server/plugins)
#   MINECRAFT_SVC  - systemd service name (default: minecraft.service)

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGINS_DIR="${PLUGINS_DIR:-$HOME/minecraft-server/plugins}"
MINECRAFT_SVC="${MINECRAFT_SVC:-minecraft.service}"
PLUGIN_JAR_PATTERN="audioviz-plugin-*.jar"

SKIP_BUILD=false
SKIP_TESTS=false
DRY_RUN=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
for arg in "$@"; do
  case $arg in
    --skip-build)  SKIP_BUILD=true ;;
    --skip-tests)  SKIP_TESTS=true ;;
    --dry-run)     DRY_RUN=true ;;
    --help|-h)
      head -12 "$0" | tail -10
      exit 0
      ;;
    *)
      echo -e "${RED}Unknown argument: $arg${NC}"
      exit 1
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() {
  echo -e "${BLUE}[deploy]${NC} $1"
}

success() {
  echo -e "${GREEN}[deploy]${NC} $1"
}

warn() {
  echo -e "${YELLOW}[deploy]${NC} $1"
}

fail() {
  echo -e "${RED}[deploy]${NC} $1"
  exit 1
}

run() {
  if $DRY_RUN; then
    echo -e "${YELLOW}[dry-run]${NC} $*"
  else
    "$@"
  fi
}

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
log "Starting deploy from $REPO_DIR"
log "Plugins dir: $PLUGINS_DIR"
log "Service: $MINECRAFT_SVC"

if [ ! -d "$REPO_DIR/minecraft_plugin" ]; then
  fail "Not in the minecraft-audio-viz repo root"
fi

if [ ! -d "$PLUGINS_DIR" ]; then
  fail "Plugins directory not found: $PLUGINS_DIR"
fi

# ---------------------------------------------------------------------------
# Step 1: Pull latest code
# ---------------------------------------------------------------------------
log "Step 1/6: Pulling latest code..."
cd "$REPO_DIR"
run git fetch origin
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
run git pull origin "$CURRENT_BRANCH"
COMMIT_HASH=$(git rev-parse --short HEAD)
log "Now at commit $COMMIT_HASH on branch $CURRENT_BRANCH"

# ---------------------------------------------------------------------------
# Step 2: Build Minecraft plugin
# ---------------------------------------------------------------------------
if $SKIP_BUILD; then
  warn "Step 2/6: Skipping Maven build (--skip-build)"
else
  log "Step 2/6: Building Minecraft plugin..."
  cd "$REPO_DIR/minecraft_plugin"
  run mvn clean package -DskipTests -q
  success "Plugin JAR built successfully"
fi

# ---------------------------------------------------------------------------
# Step 3: Copy JAR to plugins directory
# ---------------------------------------------------------------------------
log "Step 3/6: Copying plugin JAR to $PLUGINS_DIR..."
cd "$REPO_DIR/minecraft_plugin/target"

JAR_FILE=$(ls $PLUGIN_JAR_PATTERN 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
  fail "No plugin JAR found matching $PLUGIN_JAR_PATTERN in target/"
fi

# Remove old JARs from plugins dir
run rm -f "$PLUGINS_DIR"/$PLUGIN_JAR_PATTERN
run cp "$JAR_FILE" "$PLUGINS_DIR/"
success "Copied $JAR_FILE to plugins directory"

# ---------------------------------------------------------------------------
# Step 4: Install/update Python dependencies
# ---------------------------------------------------------------------------
log "Step 4/6: Installing Python dependencies..."
cd "$REPO_DIR"
run pip install -e ".[dev]" --quiet 2>&1 | tail -3
success "Python dependencies updated"

# ---------------------------------------------------------------------------
# Step 5: Run Python tests
# ---------------------------------------------------------------------------
if $SKIP_TESTS; then
  warn "Step 5/6: Skipping tests (--skip-tests)"
else
  log "Step 5/6: Running Python tests..."
  cd "$REPO_DIR"
  if run pytest audio_processor/tests/ -v --tb=short; then
    success "All tests passed"
  else
    warn "Some tests failed -- continuing deploy anyway"
  fi
fi

# ---------------------------------------------------------------------------
# Step 6: Restart services
# ---------------------------------------------------------------------------
log "Step 6/6: Restarting Minecraft server..."
run sudo systemctl restart "$MINECRAFT_SVC"

# Wait for the service to come up
log "Waiting for service to start..."
if ! $DRY_RUN; then
  sleep 8

  if systemctl is-active --quiet "$MINECRAFT_SVC"; then
    success "minecraft.service is running"
  else
    fail "minecraft.service failed to start. Check: sudo journalctl -u $MINECRAFT_SVC -n 50"
  fi
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo ""
success "==========================================="
success "  Deploy complete!"
success "  Commit:  $COMMIT_HASH"
success "  Branch:  $CURRENT_BRANCH"
success "  Plugin:  $JAR_FILE"
success "==========================================="
