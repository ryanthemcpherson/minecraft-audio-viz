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

python3 - "$RUNTIME_SOURCE" "$SCRIPT_DIR/runtime-lock.json" <<'PY'
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

runtime_source = Path(sys.argv[1])
runtime_lock = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
for architecture, elf_machine in (("linux-amd64", 62), ("linux-arm64", 183)):
    binary_path = runtime_source / architecture / "python/bin/python3.12"
    binary_path.parent.mkdir(parents=True)
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[18:20] = elf_machine.to_bytes(2, "little")
    binary_path.write_bytes(header)
    site_packages = binary_path.parents[1] / "lib/python3.12/site-packages"
    for dependency in runtime_lock["dependencies"]:
        distribution = re.sub(r"[-_.]+", "_", dependency["name"])
        dist_info = f"{distribution}-{dependency['version']}.dist-info"
        metadata_relative = f"{dist_info}/METADATA"
        module_relative = f"_mcav_fixture_{distribution}.py"
        record_relative = f"{dist_info}/RECORD"
        metadata = site_packages / metadata_relative
        module = site_packages / module_relative
        metadata_payload = (
            f"Name: {dependency['name']}\nVersion: {dependency['version']}\n"
        ).encode()
        module_payload = f'PACKAGE = "{dependency["name"]}"\n'.encode()
        metadata.parent.mkdir(parents=True, exist_ok=True)
        metadata.write_bytes(metadata_payload)
        module.write_bytes(module_payload)

        def record_row(relative_path: str, payload: bytes) -> str:
            digest = base64.urlsafe_b64encode(hashlib.sha256(payload).digest()).rstrip(b"=")
            return f"{relative_path},sha256={digest.decode()},{len(payload)}"

        record = site_packages / record_relative
        record.write_text(
            "\n".join(
                (
                    record_row(metadata_relative, metadata_payload),
                    record_row(module_relative, module_payload),
                    f"{record_relative},,",
                )
            )
            + "\n",
            encoding="utf-8",
        )
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

python3 - "$ARCHIVE" "$VERSION" "$REPO_ROOT" <<'PY'
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

archive, version, repository_root_text = sys.argv[1:]
repository_root = Path(repository_root_text)
required = {
    "plugins/AudioViz.jar",
    "mcav-vj/start-mcav.sh",
    "mcav-vj/VERSION",
    "mcav-vj/mcav.env.example",
    "mcav-vj/release/AudioViz.jar",
    "mcav-vj/release/runtime-lock.json",
    "mcav-vj/admin_panel/runtime-config.js",
    "mcav-vj/preview_tool/frontend/js/latency-indicator.js",
    "mcav-vj/preview_tool/frontend/js/vendor/three-r128.min.js",
    "mcav-vj/preview_tool/frontend/runtime-config.js",
    "mcav-vj/vj_server/web_gateway.py",
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

runtime_lock = json.loads(
    (repository_root / "deploy/pterodactyl/runtime-lock.json").read_text(encoding="utf-8")
)
locked_dependencies = {
    dependency["name"].casefold(): dependency["version"]
    for dependency in runtime_lock["dependencies"]
}
assert runtime_lock["schema_version"] == 1
for dependency in runtime_lock["dependencies"]:
    assert dependency["wheels"].keys() == {"linux-amd64", "linux-arm64"}
    for wheel in dependency["wheels"].values():
        assert wheel["filename"].endswith(".whl")
        assert re.fullmatch(r"[0-9a-f]{64}", wheel["sha256"])
expected_aiohttp_closure = {
    "aiohttp": "3.14.3",
    "aiohappyeyeballs": "2.7.1",
    "aiosignal": "1.4.0",
    "attrs": "26.1.0",
    "frozenlist": "1.8.0",
    "idna": "3.19",
    "multidict": "6.7.1",
    "propcache": "0.5.2",
    "typing-extensions": "4.16.0",
    "yarl": "1.24.5",
}
assert expected_aiohttp_closure.items() <= locked_dependencies.items(), {
    name: (version, locked_dependencies.get(name))
    for name, version in expected_aiohttp_closure.items()
    if locked_dependencies.get(name) != version
}

with zipfile.ZipFile(archive) as release_zip:
    infos = {entry.filename: entry for entry in release_zip.infolist() if not entry.is_dir()}
    assert required <= infos.keys(), required - infos.keys()
    assert len(infos) == len(set(infos)), "duplicate ZIP entries"
    assert all(name.startswith("mcav-vj/") or name == "plugins/AudioViz.jar" for name in infos)
    assert release_zip.read("mcav-vj/VERSION").decode() == version
    assert release_zip.read("mcav-vj/release/AudioViz.jar") == b"fixture-plugin"
    assert release_zip.read("plugins/AudioViz.jar") == b"fixture-plugin"
    assert json.loads(release_zip.read("mcav-vj/release/runtime-lock.json")) == runtime_lock
    environment = release_zip.read("mcav-vj/mcav.env.example").decode("utf-8")
    assert "HTTP_PORT=25927" in environment
    assert "VJ_SERVER_PORT=25808" in environment
    assert "UNIFIED_WEB=true" in environment
    for name in executables:
        assert ((infos[name].external_attr >> 16) & 0o111) != 0, name

    manifest = {}
    for line in release_zip.read("mcav-vj/MANIFEST.sha256").decode().splitlines():
        digest, name = line.split("  ", 1)
        manifest[name] = digest
    payload_names = {
        name.removeprefix("mcav-vj/")
        for name in infos
        if name.startswith("mcav-vj/") and name != "mcav-vj/MANIFEST.sha256"
    }
    assert manifest.keys() == payload_names
    for relative_name, expected_digest in manifest.items():
        payload = release_zip.read(f"mcav-vj/{relative_name}")
        assert hashlib.sha256(payload).hexdigest() == expected_digest, relative_name

deployment = (repository_root / "docs/deployment/PTERODACTYL.md").read_text(encoding="utf-8")
allocation_section = deployment.split("## Allocations", 1)[1].split("##", 1)[0]
assert re.findall(r"(?m)^- `(\d+)`", allocation_section) == ["25927", "25808"]
assert "/ws" in allocation_section
assert "8766" not in allocation_section
assert "9000" not in allocation_section
assert "MCAV_PUBLIC_HOST=<public-ip>" in deployment
assert "state/tls.crt" in deployment
assert "TLS_SHA256_FINGERPRINT" in deployment
assert "Get-FileHash" not in deployment
assert "ComputeHash($der)" in deployment
assert "--rotate-tls-identity" in deployment
assert "trust-on-first-use" in deployment.casefold()
assert "ws://" not in deployment.casefold()
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

python3 "$SCRIPT_DIR/tests/test_runtime_lock.py"
python3 "$SCRIPT_DIR/tests/test_release_verifier_parity.py"

printf 'Pterodactyl release packaging test passed.\n'
