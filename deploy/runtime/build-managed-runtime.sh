#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_PYTHON="$REPO_ROOT/vj_server/.venv/bin/python"
if [[ ! -x "$BUILD_PYTHON" ]]; then
  BUILD_PYTHON="$(command -v python3)"
fi

PLATFORM=""
OUTPUT=""
VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform)
      PLATFORM="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:-}"
      shift 2
      ;;
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown managed-runtime build argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$PLATFORM" || -z "$OUTPUT" ]]; then
  echo "--platform and --output are required" >&2
  exit 2
fi

if [[ -n "$VERSION" ]]; then
  exec "$BUILD_PYTHON" "$SCRIPT_DIR/build_release_runtime.py" \
    --target "$PLATFORM" --version "$VERSION" --output "$OUTPUT"
fi

exec "$BUILD_PYTHON" "$SCRIPT_DIR/build_managed_runtime.py" \
  --platform "$PLATFORM" --output "$OUTPUT"
