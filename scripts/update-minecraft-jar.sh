#!/usr/bin/env bash
set -euo pipefail

# Update Minecraft server jar (Paper by default) and restart service only when changed.
#
# Examples:
#   ./scripts/update-minecraft-jar.sh
#   ./scripts/update-minecraft-jar.sh --mc-version 1.21.11
#   ./scripts/update-minecraft-jar.sh --provider url --jar-url https://example.com/purpur.jar

SERVER_DIR="${SERVER_DIR:-/home/ryan/minecraft-server}"
SERVICE_NAME="${SERVICE_NAME:-minecraft.service}"
JAR_NAME="${JAR_NAME:-paper.jar}"
PROVIDER="paper"
MC_VERSION=""
PROJECT="paper"
JAR_URL=""
CHECKSUM=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --server-dir) SERVER_DIR="$2"; shift 2 ;;
        --service) SERVICE_NAME="$2"; shift 2 ;;
        --jar-name) JAR_NAME="$2"; shift 2 ;;
        --provider) PROVIDER="$2"; shift 2 ;;
        --project) PROJECT="$2"; shift 2 ;;
        --mc-version) MC_VERSION="$2"; shift 2 ;;
        --jar-url) JAR_URL="$2"; shift 2 ;;
        --sha256) CHECKSUM="$2"; shift 2 ;;
        -h|--help)
            cat <<'EOF'
Usage: update-minecraft-jar.sh [options]

Options:
  --provider paper|url   Source type (default: paper)
  --project NAME         Paper project id (default: paper)
  --mc-version VERSION   Minecraft version (auto-detect when omitted)
  --jar-url URL          Direct jar URL when --provider url
  --sha256 HASH          Optional SHA256 for --provider url
  --server-dir DIR       Server directory (default: /home/ryan/minecraft-server)
  --jar-name NAME        Target jar name in server dir (default: paper.jar)
  --service NAME         Systemd service name (default: minecraft.service)
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

require_cmd() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "ERROR: required command not found: $1" >&2
        exit 1
    fi
}

require_cmd curl
require_cmd sha256sum
require_cmd systemctl
require_cmd python3

run_systemctl() {
    # Prefer direct systemctl; fallback to passwordless sudo in automation contexts.
    if systemctl "$@"; then
        return 0
    fi

    if command -v sudo >/dev/null 2>&1; then
        sudo -n systemctl "$@"
        return 0
    fi

    echo "ERROR: insufficient privileges to run systemctl $*" >&2
    exit 1
}

mkdir -p "${SERVER_DIR}"
TARGET_JAR="${SERVER_DIR}/${JAR_NAME}"
TMP_JAR="$(mktemp)"
cleanup() {
    rm -f "${TMP_JAR}"
}
trap cleanup EXIT

download_url=""
expected_sha=""
release_label=""

if [[ "${PROVIDER}" == "paper" ]]; then
    if [[ -z "${MC_VERSION}" && -f "${SERVER_DIR}/version_history.json" ]]; then
        MC_VERSION="$(
            python3 -c 'import json,re,sys; d=json.load(open(sys.argv[1],"r",encoding="utf-8")); s=d.get("currentVersion",""); m=re.search(r"(\d+\.\d+(?:\.\d+)?)", s); print(m.group(1) if m else "")' \
                "${SERVER_DIR}/version_history.json"
        )"
    fi

    if [[ -z "${MC_VERSION}" ]]; then
        echo "ERROR: could not determine mc version. Pass --mc-version." >&2
        exit 1
    fi

    build_json="$(curl -fsSL "https://api.papermc.io/v2/projects/${PROJECT}/versions/${MC_VERSION}/builds")"
    read -r latest_build latest_name latest_sha < <(
        python3 -c 'import json,sys; data=json.loads(sys.stdin.read()); b=data["builds"][-1]; d=b["downloads"]["application"]; print("{} {} {}".format(b["build"], d["name"], d["sha256"]))' <<<"${build_json}"
    )

    download_url="https://api.papermc.io/v2/projects/${PROJECT}/versions/${MC_VERSION}/builds/${latest_build}/downloads/${latest_name}"
    expected_sha="${latest_sha}"
    release_label="${PROJECT}-${MC_VERSION}-${latest_build}"
elif [[ "${PROVIDER}" == "url" ]]; then
    if [[ -z "${JAR_URL}" ]]; then
        echo "ERROR: --jar-url is required when --provider url" >&2
        exit 1
    fi
    download_url="${JAR_URL}"
    expected_sha="${CHECKSUM}"
    release_label="${JAR_URL}"
else
    echo "ERROR: unsupported provider '${PROVIDER}'. Use 'paper' or 'url'." >&2
    exit 1
fi

echo "Checking jar update source: ${release_label}"
curl -fsSL "${download_url}" -o "${TMP_JAR}"

new_sha="$(sha256sum "${TMP_JAR}" | awk '{print $1}')"
if [[ -n "${expected_sha}" && "${new_sha}" != "${expected_sha}" ]]; then
    echo "ERROR: checksum mismatch for downloaded jar" >&2
    echo "Expected: ${expected_sha}" >&2
    echo "Actual:   ${new_sha}" >&2
    exit 1
fi

current_sha=""
if [[ -f "${TARGET_JAR}" ]]; then
    current_sha="$(sha256sum "${TARGET_JAR}" | awk '{print $1}')"
fi

if [[ -n "${current_sha}" && "${current_sha}" == "${new_sha}" ]]; then
    echo "No update needed. ${JAR_NAME} is already current (${new_sha})."
    exit 0
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
if [[ -f "${TARGET_JAR}" ]]; then
    cp -f "${TARGET_JAR}" "${TARGET_JAR}.bak-${timestamp}"
fi

install -m 0644 "${TMP_JAR}" "${TARGET_JAR}"
echo "Updated ${TARGET_JAR} (sha256=${new_sha})"

echo "Restarting ${SERVICE_NAME}..."
run_systemctl restart "${SERVICE_NAME}"
sleep 10

if run_systemctl is-active --quiet "${SERVICE_NAME}"; then
    echo "Service ${SERVICE_NAME} is active."
else
    echo "ERROR: ${SERVICE_NAME} failed to start after jar update." >&2
    if command -v journalctl >/dev/null 2>&1; then
        journalctl -u "${SERVICE_NAME}" --no-pager -n 80 || true
    fi
    exit 1
fi
