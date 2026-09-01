#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_PYTHON="$REPO_ROOT/vj_server/.venv/bin/python"
if [[ ! -x "$BUILD_PYTHON" ]]; then
  BUILD_PYTHON="$(command -v python3)"
fi

exec "$BUILD_PYTHON" "$SCRIPT_DIR/build_managed_runtime.py" "$@"
