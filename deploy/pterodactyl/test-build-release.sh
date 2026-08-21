#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT

RUNTIME_SOURCE="$TEMP_ROOT/runtimes"
OUTPUT_DIR="$TEMP_ROOT/output"
PLUGIN_JAR="$TEMP_ROOT/AudioViz.jar"
VERSION="26.1-release+test"

python3 - "$RUNTIME_SOURCE" <<'PY'
import sys
from pathlib import Path

runtime_source = Path(sys.argv[1])
for architecture, elf_machine in (("linux-amd64", 62), ("linux-arm64", 183)):
    binary_path = runtime_source / architecture / "python/bin/python3.12"
    binary_path.parent.mkdir(parents=True)
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[18:20] = elf_machine.to_bytes(2, "little")
    binary_path.write_bytes(header)
PY
for architecture in linux-amd64 linux-arm64; do
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'if [[ -n "${MCAV_SMOKE_MARKER:-}" ]]; then touch "$MCAV_SMOKE_MARKER"; fi' \
    'if [[ "${PYTHONDONTWRITEBYTECODE:-}" != "1" ]]; then' \
    '  mkdir -p "$(dirname "$0")/python/lib/__pycache__"' \
    '  touch "$(dirname "$0")/python/lib/__pycache__/smoke.pyc"' \
    'fi' \
    'exit 0' \
    > "$RUNTIME_SOURCE/$architecture/audioviz-vj"
  chmod 755 \
    "$RUNTIME_SOURCE/$architecture/audioviz-vj" \
    "$RUNTIME_SOURCE/$architecture/python/bin/python3.12"
done
printf 'fixture-plugin' > "$PLUGIN_JAR"
export MCAV_SMOKE_MARKER="$TEMP_ROOT/smoke-ran"

bash "$SCRIPT_DIR/build-release.sh" \
  --version "$VERSION" \
  --plugin-jar "$PLUGIN_JAR" \
  --runtime-source "$RUNTIME_SOURCE" \
  --output-dir "$OUTPUT_DIR"
test -f "$MCAV_SMOKE_MARKER"

ARCHIVE="$OUTPUT_DIR/mcav-pterodactyl-$VERSION.zip"
CHECKSUM="$ARCHIVE.sha256"
test -f "$ARCHIVE"
test -f "$CHECKSUM"

(
  cd "$OUTPUT_DIR"
  sha256sum --check "$(basename "$CHECKSUM")"
)

python3 - "$ARCHIVE" "$VERSION" <<'PY'
import hashlib
import sys
import zipfile

archive, version = sys.argv[1:]
required = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/VERSION",
    "mcav-vj/release/AudioViz.jar",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
    "mcav-vj/MANIFEST.sha256",
}
executables = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
}

with zipfile.ZipFile(archive) as release_zip:
    infos = {entry.filename: entry for entry in release_zip.infolist() if not entry.is_dir()}
    assert required <= infos.keys(), required - infos.keys()
    assert len(infos) == len(set(infos)), "duplicate ZIP entries"
    assert all(name.startswith("mcav-vj/") for name in infos)
    assert release_zip.read("mcav-vj/VERSION").decode() == version
    assert release_zip.read("mcav-vj/release/AudioViz.jar") == b"fixture-plugin"
    for name in executables:
        assert ((infos[name].external_attr >> 16) & 0o111) != 0, name

    manifest = {}
    for line in release_zip.read("mcav-vj/MANIFEST.sha256").decode().splitlines():
        digest, name = line.split("  ", 1)
        manifest[name] = digest
    payload_names = {
        name.removeprefix("mcav-vj/")
        for name in infos
        if name != "mcav-vj/MANIFEST.sha256"
    }
    assert manifest.keys() == payload_names
    for relative_name, expected_digest in manifest.items():
        payload = release_zip.read(f"mcav-vj/{relative_name}")
        assert hashlib.sha256(payload).hexdigest() == expected_digest, relative_name
PY

MISSING_SERVER_ARCHIVE="$OUTPUT_DIR/missing-vj-server.zip"
python3 - "$ARCHIVE" "$MISSING_SERVER_ARCHIVE" <<'PY'
import sys
import zipfile

source_path, target_path = sys.argv[1:]
with zipfile.ZipFile(source_path) as source, zipfile.ZipFile(target_path, "w") as target:
    for entry in source.infolist():
        if entry.filename == "mcav-vj/vj_server/cli.py":
            continue
        payload = source.read(entry.filename)
        if entry.filename == "mcav-vj/MANIFEST.sha256":
            lines = payload.decode().splitlines()
            payload = ("\n".join(line for line in lines if not line.endswith("  vj_server/cli.py")) + "\n").encode()
        target.writestr(entry, payload)
PY
if python3 "$SCRIPT_DIR/release_archive.py" verify "$MISSING_SERVER_ARCHIVE" \
  > "$TEMP_ROOT/missing-server.log" 2>&1; then
  printf 'Verifier accepted a release without vj_server/cli.py.\n' >&2
  exit 1
fi
grep -q 'Required release entries missing: mcav-vj/vj_server/cli.py' "$TEMP_ROOT/missing-server.log"

WRONG_RUNTIME_SOURCE="$TEMP_ROOT/wrong-runtimes"
cp -a "$RUNTIME_SOURCE" "$WRONG_RUNTIME_SOURCE"
cp \
  "$WRONG_RUNTIME_SOURCE/linux-amd64/python/bin/python3.12" \
  "$WRONG_RUNTIME_SOURCE/linux-arm64/python/bin/python3.12"
if bash "$SCRIPT_DIR/build-release.sh" \
  --version "26.1-wrong-architecture" \
  --plugin-jar "$PLUGIN_JAR" \
  --runtime-source "$WRONG_RUNTIME_SOURCE" \
  --output-dir "$TEMP_ROOT/wrong-output" \
  > "$TEMP_ROOT/wrong-architecture.log" 2>&1; then
  printf 'Verifier accepted an AMD64 Python binary in the ARM64 runtime.\n' >&2
  exit 1
fi
grep -q 'linux-arm64 Python executable has ELF machine 62; expected 183' \
  "$TEMP_ROOT/wrong-architecture.log"

printf 'Pterodactyl release packaging test passed.\n'
