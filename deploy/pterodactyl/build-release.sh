#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
VERSION=""
PLUGIN_JAR=""
RUNTIME_SOURCE=""
OUTPUT_DIR="$REPO_ROOT/dist"

usage() {
  printf 'Usage: %s --version VERSION --plugin-jar FILE [--runtime-source DIR] [--output-dir DIR]\n' "$0" >&2
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --plugin-jar)
      PLUGIN_JAR="${2:-}"
      shift 2
      ;;
    --runtime-source)
      RUNTIME_SOURCE="${2:-}"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="${2:-}"
      shift 2
      ;;
    *)
      usage
      exit 64
      ;;
  esac
done

if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]*$ ]] || [[ ! -f "$PLUGIN_JAR" ]]; then
  usage
  exit 64
fi
if [[ -n "$RUNTIME_SOURCE" && ! -d "$RUNTIME_SOURCE" ]]; then
  printf 'Runtime source does not exist: %s\n' "$RUNTIME_SOURCE" >&2
  exit 66
fi

PLUGIN_JAR="$(cd "$(dirname "$PLUGIN_JAR")" && pwd)/$(basename "$PLUGIN_JAR")"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
STAGING_ROOT="$(mktemp -d)"
trap 'rm -rf "$STAGING_ROOT"' EXIT
MCAV_ROOT="$STAGING_ROOT/mcav-vj"
mkdir -p "$MCAV_ROOT/release" "$MCAV_ROOT/configs"

install -m 644 "$PLUGIN_JAR" "$MCAV_ROOT/release/AudioViz.jar"
install -m 644 "$SCRIPT_DIR/plugin-config.default.yml" "$MCAV_ROOT/release/plugin-config.default.yml"
install -m 755 "$SCRIPT_DIR/start-mcav.sh" "$MCAV_ROOT/start-mcav.sh"
install -m 644 "$SCRIPT_DIR/mcav.env.example" "$MCAV_ROOT/mcav.env.example"
printf '%s' "$VERSION" > "$MCAV_ROOT/VERSION"

copy_tracked_file() {
  local relative_path="$1"
  local destination="$MCAV_ROOT/$relative_path"
  mkdir -p "$(dirname "$destination")"
  install -m 644 "$REPO_ROOT/$relative_path" "$destination"
}

GIT_COMMAND=(git -C "$REPO_ROOT")
if [[ -f "$REPO_ROOT/.git" ]]; then
  WORKTREE_GIT_DIR="$(sed -n 's/^gitdir: //p' "$REPO_ROOT/.git")"
  if [[ "$WORKTREE_GIT_DIR" =~ ^[A-Za-z]:[/\\] ]]; then
    if ! command -v wslpath > /dev/null; then
      printf 'Cannot resolve Windows linked-worktree Git directory without wslpath.\n' >&2
      exit 69
    fi
    WORKTREE_GIT_DIR="$(wslpath -u "$WORKTREE_GIT_DIR")"
  elif [[ "$WORKTREE_GIT_DIR" != /* ]]; then
    WORKTREE_GIT_DIR="$(realpath -m "$REPO_ROOT/$WORKTREE_GIT_DIR")"
  fi
  GIT_COMMAND=(git "--git-dir=$WORKTREE_GIT_DIR" "--work-tree=$REPO_ROOT")
fi

while IFS= read -r -d '' relative_path; do
  case "$relative_path" in
    vj_server/*.py)
      if [[ "${relative_path#vj_server/}" == */* ]]; then
        continue
      fi
      ;;
  esac
  copy_tracked_file "$relative_path"
done < <(
  "${GIT_COMMAND[@]}" ls-files -z -- \
    'vj_server/*.py' \
    admin_panel \
    preview_tool/frontend \
    patterns \
    configs/dj_auth.example.json \
    configs/scenes \
    configs/banners
)

if [[ -n "$RUNTIME_SOURCE" ]]; then
  mkdir -p "$MCAV_ROOT/bin"
  cp -a "$RUNTIME_SOURCE/." "$MCAV_ROOT/bin/"
else
  "$SCRIPT_DIR/build-runtime.sh" "$MCAV_ROOT"
fi

case "$(uname -m)" in
  x86_64|amd64)
    NATIVE_LAUNCHER="$MCAV_ROOT/bin/linux-amd64/audioviz-vj"
    ;;
  aarch64|arm64)
    NATIVE_LAUNCHER="$MCAV_ROOT/bin/linux-arm64/audioviz-vj"
    ;;
  *)
    printf 'Unsupported build architecture: %s\n' "$(uname -m)" >&2
    exit 69
    ;;
esac
PYTHONDONTWRITEBYTECODE=1 "$NATIVE_LAUNCHER" --help > /dev/null

ARCHIVE="$OUTPUT_DIR/mcav-pterodactyl-$VERSION.zip"
CHECKSUM="$ARCHIVE.sha256"
python3 "$SCRIPT_DIR/release_archive.py" create "$MCAV_ROOT" "$ARCHIVE"
(
  cd "$OUTPUT_DIR"
  sha256sum "$(basename "$ARCHIVE")" > "$(basename "$CHECKSUM")"
)

printf 'Release: %s\n' "$ARCHIVE"
printf 'Checksum: %s\n' "$CHECKSUM"
