#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  printf 'Usage: %s OUTPUT_MCAV_ROOT\n' "$0" >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_ROOT="$(mkdir -p "$1" && cd "$1" && pwd)"
if [[ "$OUTPUT_ROOT" == / ]] || [[ "$OUTPUT_ROOT" == "$HOME" ]]; then
  printf 'Refusing unsafe runtime output root: %s\n' "$OUTPUT_ROOT" >&2
  exit 64
fi
mkdir -p "$OUTPUT_ROOT/bin"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT

BUILD_PYTHON="$REPO_ROOT/vj_server/.venv/bin/python"
if [[ ! -x "$BUILD_PYTHON" ]]; then
  BUILD_PYTHON="$(command -v python3)"
fi

for mapping in linux-amd64:linux-x86_64 linux-arm64:linux-aarch64; do
  architecture="${mapping%%:*}"
  platform="${mapping#*:}"
  build_output="$TEMP_ROOT/$platform"
  "$REPO_ROOT/deploy/runtime/build-managed-runtime.sh" \
    --platform "$platform" \
    --output "$build_output"
  archive="$build_output/mcav-runtime-$platform.zip"
  runtime_root="$OUTPUT_ROOT/bin/$architecture"

  if [[ ! -f "$archive" ]]; then
    printf 'Managed runtime build did not produce %s.\n' "$platform" >&2
    exit 1
  fi

  rm -rf "$runtime_root"
  mkdir -p "$runtime_root"
  "$BUILD_PYTHON" - "$archive" "$runtime_root" <<'PY'
import shutil
import sys
import zipfile
from pathlib import Path, PurePosixPath

archive_path, output_value = sys.argv[1:]
output = Path(output_value)
with zipfile.ZipFile(archive_path) as archive:
    for entry in archive.infolist():
        path = PurePosixPath(entry.filename)
        if (
            entry.is_dir()
            or not path.parts
            or path.parts[0] != "python"
            or "__pycache__" in path.parts
            or path.suffix in {".pyc", ".pyo"}
        ):
            continue
        destination = output.joinpath(*path.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(entry) as source, destination.open("wb") as target:
            shutil.copyfileobj(source, target, length=1024 * 1024)
PY

  launcher="$runtime_root/audioviz-vj"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'RUNTIME_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"' \
    'PROJECT_ROOT="$(cd "$RUNTIME_DIR/../.." && pwd)"' \
    'export PYTHONPATH="$PROJECT_ROOT${PYTHONPATH:+:$PYTHONPATH}"' \
    'exec "$RUNTIME_DIR/python/bin/python3.12" -m vj_server.cli "$@"' \
    > "$launcher"
  chmod 755 "$launcher" "$runtime_root/python/bin/python3.12"

done

native_arch="$(uname -m)"
if [[ "$native_arch" == x86_64 ]]; then
  "$OUTPUT_ROOT/bin/linux-amd64/python/bin/python3.12" -c \
    'import aiohttp,bcrypt,cryptography,lupa,msgspec,numpy,websockets; print("AMD64 runtime imports passed")'
elif [[ "$native_arch" == aarch64 ]]; then
  "$OUTPUT_ROOT/bin/linux-arm64/python/bin/python3.12" -c \
    'import aiohttp,bcrypt,cryptography,lupa,msgspec,numpy,websockets; print("ARM64 runtime imports passed")'
fi
