#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  printf 'Usage: %s OUTPUT_MCAV_ROOT\n' "$0" >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOCK_FILE="$SCRIPT_DIR/runtime-lock.json"
LOCK_TOOL="$SCRIPT_DIR/runtime_lock.py"
OUTPUT_ROOT="$(mkdir -p "$1" && cd "$1" && pwd)"
CACHE_DIR="$SCRIPT_DIR/.cache"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT
mkdir -p "$CACHE_DIR/wheels" "$OUTPUT_ROOT/bin"

BUILD_PYTHON="$REPO_ROOT/vj_server/.venv/bin/python"
if [[ ! -x "$BUILD_PYTHON" ]]; then
  BUILD_PYTHON="$(command -v python3)"
fi

mapfile -t dependencies < <(
  "$BUILD_PYTHON" "$LOCK_TOOL" requirements "$LOCK_FILE"
)

for architecture in linux-amd64 linux-arm64; do
  url="$($BUILD_PYTHON -c 'import json,sys; print(json.load(open(sys.argv[1]))["runtimes"][sys.argv[2]]["url"])' "$LOCK_FILE" "$architecture")"
  expected_sha="$($BUILD_PYTHON -c 'import json,sys; print(json.load(open(sys.argv[1]))["runtimes"][sys.argv[2]]["sha256"])' "$LOCK_FILE" "$architecture")"
  archive="$CACHE_DIR/${architecture}-python.tar.gz"
  runtime_root="$OUTPUT_ROOT/bin/$architecture"
  wheelhouse="$CACHE_DIR/wheels/$architecture"

  if [[ ! -f "$archive" ]] || [[ "$(sha256sum "$archive" | cut -d' ' -f1)" != "$expected_sha" ]]; then
    rm -f "$archive"
    printf 'Downloading %s portable Python...\n' "$architecture"
    curl --fail --location --retry 3 --output "$archive" "$url"
  fi
  actual_sha="$(sha256sum "$archive" | cut -d' ' -f1)"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    printf 'SHA-256 mismatch for %s: expected %s, got %s\n' "$architecture" "$expected_sha" "$actual_sha" >&2
    exit 1
  fi

  rm -rf "$runtime_root"
  mkdir -p "$runtime_root"
  tar -xzf "$archive" -C "$runtime_root"
  site_packages="$runtime_root/python/lib/python3.12/site-packages"
  install_root="$TEMP_ROOT/install-$architecture"
  mkdir -p "$site_packages" "$install_root"

  mapfile -t platforms < <(
    "$BUILD_PYTHON" -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["runtimes"][sys.argv[2]]["pip_platforms"]))' "$LOCK_FILE" "$architecture"
  )
  platform_args=()
  for platform in "${platforms[@]}"; do
    platform_args+=(--platform "$platform")
  done

  if [[ ! -d "$wheelhouse" ]]; then
    download_root="$TEMP_ROOT/wheels-$architecture"
    mkdir -p "$download_root"
    "$BUILD_PYTHON" -m pip download \
      --disable-pip-version-check \
      --no-deps \
      --only-binary=:all: \
      --implementation cp \
      --python-version 3.12 \
      "${platform_args[@]}" \
      --dest "$download_root" \
      "${dependencies[@]}"
    "$BUILD_PYTHON" "$LOCK_TOOL" verify-wheelhouse \
      "$LOCK_FILE" "$architecture" "$download_root" > /dev/null
    mv "$download_root" "$wheelhouse"
  fi

  mapfile -t wheel_paths < <(
    "$BUILD_PYTHON" "$LOCK_TOOL" verify-wheelhouse \
      "$LOCK_FILE" "$architecture" "$wheelhouse"
  )
  "$BUILD_PYTHON" -m pip install \
    --disable-pip-version-check \
    --no-compile \
    --no-deps \
    --no-index \
    --only-binary=:all: \
    --implementation cp \
    --python-version 3.12 \
    "${platform_args[@]}" \
    --target "$install_root" \
    "${wheel_paths[@]}"
  "$BUILD_PYTHON" "$LOCK_TOOL" normalize-target-records \
    "$LOCK_FILE" "$architecture" "$install_root" > /dev/null
  "$BUILD_PYTHON" "$LOCK_TOOL" install-staged \
    "$LOCK_FILE" "$architecture" "$install_root" "$site_packages" > /dev/null

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

  find "$runtime_root" -type d -name __pycache__ -prune -exec rm -rf {} +
  find "$runtime_root/python" -type d \( -iname test -o -iname tests \) -prune -exec rm -rf {} +
  find "$runtime_root/python" -type f \( -iname '*.test.*' -o -iname '*.spec.*' \) -delete
  find "$runtime_root" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete
  rm -rf "$runtime_root/python/share" "$runtime_root/python/include" "$runtime_root/python/lib/pkgconfig"
  find "$runtime_root/python/bin" -mindepth 1 -maxdepth 1 ! -name python3.12 -delete
  find "$runtime_root/python" -type l -delete
  "$BUILD_PYTHON" "$LOCK_TOOL" prune-records \
    "$LOCK_FILE" "$architecture" "$site_packages" > /dev/null
  "$BUILD_PYTHON" "$LOCK_TOOL" verify-install \
    "$LOCK_FILE" "$architecture" "$site_packages" > /dev/null
done

native_arch="$(uname -m)"
if [[ "$native_arch" == x86_64 ]]; then
  PYTHONDONTWRITEBYTECODE=1 "$OUTPUT_ROOT/bin/linux-amd64/python/bin/python3.12" -c \
    'import aiohttp,bcrypt,lupa,msgspec,numpy,websockets; print("AMD64 runtime imports passed")'
elif [[ "$native_arch" == aarch64 ]]; then
  PYTHONDONTWRITEBYTECODE=1 "$OUTPUT_ROOT/bin/linux-arm64/python/bin/python3.12" -c \
    'import aiohttp,bcrypt,lupa,msgspec,numpy,websockets; print("ARM64 runtime imports passed")'
fi
