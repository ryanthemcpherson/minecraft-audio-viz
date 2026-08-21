#!/usr/bin/env python3
"""Create and verify portable MCAV Pterodactyl release archives."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import stat
import tempfile
import zipfile
from pathlib import Path

ARCHIVE_ROOT = "mcav-vj/"
MANIFEST_NAME = "mcav-vj/MANIFEST.sha256"
REQUIRED_ENTRIES = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/VERSION",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
    "mcav-vj/release/AudioViz.jar",
    "mcav-vj/release/plugin-config.default.yml",
    "mcav-vj/vj_server/__init__.py",
    "mcav-vj/vj_server/auth.py",
    "mcav-vj/vj_server/cli.py",
    "mcav-vj/vj_server/config.py",
    "mcav-vj/vj_server/patterns.py",
    "mcav-vj/vj_server/vj_server.py",
    "mcav-vj/patterns/lib.lua",
    "mcav-vj/patterns/bars.lua",
    "mcav-vj/admin_panel/index.html",
    "mcav-vj/preview_tool/frontend/index.html",
    MANIFEST_NAME,
}
EXECUTABLE_ENTRIES = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
}
EXPECTED_ELF_MACHINES = {
    "mcav-vj/bin/linux-amd64/python/bin/python3.12": 62,
    "mcav-vj/bin/linux-arm64/python/bin/python3.12": 183,
}
FORBIDDEN_PATH = re.compile(
    r"(^|/)(node_modules|\.git|\.venv|__pycache__|tests?)(/|$)"
    r"|\.(pyc|pyo)$|(^|/)[^/]+\.(test|spec)\.[^/]+$",
    re.IGNORECASE,
)
FORBIDDEN_ENTRIES = {
    "mcav-vj/state/dj_auth.json",
    "mcav-vj/state/runtime.env",
    "mcav-vj/state/tls.key",
    "mcav-vj/FIRST_LOGIN.txt",
}


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def write_manifest(release_root: Path) -> None:
    manifest_path = release_root / "MANIFEST.sha256"
    lines = []
    for file_path in sorted(path for path in release_root.rglob("*") if path.is_file()):
        if file_path == manifest_path:
            continue
        relative_path = file_path.relative_to(release_root).as_posix()
        lines.append(f"{sha256_bytes(file_path.read_bytes())}  {relative_path}")
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def create_archive(release_root: Path, archive_path: Path) -> None:
    if release_root.name != ARCHIVE_ROOT.rstrip("/"):
        raise ValueError(f"Release root must be named {ARCHIVE_ROOT.rstrip('/')}: {release_root}")
    write_manifest(release_root)
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{archive_path.name}.", suffix=".tmp", dir=archive_path.parent, delete=False
    ) as temporary_file:
        temporary_path = Path(temporary_file.name)
    try:
        with zipfile.ZipFile(
            temporary_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as release_zip:
            for file_path in sorted(path for path in release_root.rglob("*") if path.is_file()):
                archive_name = f"{ARCHIVE_ROOT}{file_path.relative_to(release_root).as_posix()}"
                file_mode = stat.S_IMODE(file_path.stat().st_mode)
                archive_info = zipfile.ZipInfo.from_file(file_path, archive_name)
                archive_info.create_system = 3
                archive_info.external_attr = (stat.S_IFREG | file_mode) << 16
                archive_info.compress_type = zipfile.ZIP_DEFLATED
                with file_path.open("rb") as source, release_zip.open(archive_info, "w") as target:
                    while chunk := source.read(1024 * 1024):
                        target.write(chunk)
        os.replace(temporary_path, archive_path)
    finally:
        temporary_path.unlink(missing_ok=True)


def parse_manifest(payload: bytes) -> dict[str, str]:
    manifest: dict[str, str] = {}
    for line in payload.decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise ValueError(f"Malformed manifest line: {line}")
        digest, relative_path = match.groups()
        if relative_path in manifest:
            raise ValueError(f"Duplicate manifest entry: {relative_path}")
        manifest[relative_path] = digest
    return manifest


def read_elf_machine(header: bytes, executable_name: str) -> int:
    if len(header) < 20 or header[:4] != b"\x7fELF" or header[4] != 2:
        raise ValueError(f"{executable_name} is not a 64-bit ELF executable")
    if header[5] not in {1, 2}:
        raise ValueError(f"{executable_name} has an unsupported ELF byte order")
    byte_order = "little" if header[5] == 1 else "big"
    return int.from_bytes(header[18:20], byte_order)


def verify_archive(archive_path: Path) -> int:
    with zipfile.ZipFile(archive_path) as release_zip:
        entries = [entry for entry in release_zip.infolist() if not entry.is_dir()]
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("Release archive contains duplicate ZIP entries")
        if any(not name.startswith(ARCHIVE_ROOT) for name in names):
            raise ValueError(f"Every release entry must be under {ARCHIVE_ROOT}")

        missing_entries = REQUIRED_ENTRIES.difference(names)
        if missing_entries:
            raise ValueError(
                f"Required release entries missing: {', '.join(sorted(missing_entries))}"
            )
        forbidden_entries = [
            name for name in names if FORBIDDEN_PATH.search(name) or name in FORBIDDEN_ENTRIES
        ]
        if forbidden_entries:
            raise ValueError(
                f"Forbidden development or secret entries: {', '.join(forbidden_entries)}"
            )

        runtime_roots = {
            name.split("/", 3)[2]
            for name in names
            if name.startswith("mcav-vj/bin/") and len(name.split("/", 3)) == 4
        }
        if runtime_roots != {"linux-amd64", "linux-arm64"}:
            raise ValueError(
                f"Unexpected runtime architectures: {', '.join(sorted(runtime_roots))}"
            )

        entries_by_name = {entry.filename: entry for entry in entries}
        for executable_name in EXECUTABLE_ENTRIES:
            unix_mode = entries_by_name[executable_name].external_attr >> 16
            if unix_mode & 0o111 == 0:
                raise ValueError(f"Executable mode is missing from: {executable_name}")
        for executable_name, expected_machine in EXPECTED_ELF_MACHINES.items():
            with release_zip.open(executable_name) as executable:
                actual_machine = read_elf_machine(executable.read(20), executable_name)
            if actual_machine != expected_machine:
                architecture = executable_name.split("/", 4)[2]
                raise ValueError(
                    f"{architecture} Python executable has ELF machine {actual_machine}; "
                    f"expected {expected_machine}"
                )

        manifest = parse_manifest(release_zip.read(MANIFEST_NAME))
        payload_names = {name.removeprefix(ARCHIVE_ROOT) for name in names if name != MANIFEST_NAME}
        if manifest.keys() != payload_names:
            missing_manifest = sorted(payload_names.difference(manifest))
            extra_manifest = sorted(manifest.keys() - payload_names)
            raise ValueError(
                f"Manifest coverage mismatch; missing={missing_manifest}, extra={extra_manifest}"
            )
        for relative_path, expected_digest in manifest.items():
            actual_digest = sha256_bytes(release_zip.read(f"{ARCHIVE_ROOT}{relative_path}"))
            if actual_digest != expected_digest:
                raise ValueError(f"Manifest digest mismatch: {relative_path}")
        return len(entries)


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    create_parser = subparsers.add_parser("create")
    create_parser.add_argument("release_root", type=Path)
    create_parser.add_argument("archive", type=Path)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("archive", type=Path)
    arguments = parser.parse_args()

    if arguments.command == "create":
        create_archive(arguments.release_root.resolve(), arguments.archive.resolve())
        entry_count = verify_archive(arguments.archive.resolve())
        print(
            f"Created and verified release: {arguments.archive.resolve()} ({entry_count} entries)"
        )
    else:
        entry_count = verify_archive(arguments.archive.resolve())
        print(f"Verified release: {arguments.archive.resolve()} ({entry_count} entries)")


if __name__ == "__main__":
    main()
