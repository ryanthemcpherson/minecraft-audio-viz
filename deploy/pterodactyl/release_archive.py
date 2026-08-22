#!/usr/bin/env python3
"""Create and verify portable MCAV Pterodactyl release archives."""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import io
import json
import os
import re
import stat
import tempfile
import zipfile
from email.parser import BytesParser
from pathlib import Path

from runtime_lock import LockError, normalize_package_name, validate_lock

ARCHIVE_ROOT = "mcav-vj/"
MANIFEST_NAME = "mcav-vj/MANIFEST.sha256"
REQUIRED_ENTRIES = {
    "mcav-vj/start-mcav.sh",
    "mcav-vj/VERSION",
    "mcav-vj/mcav.env.example",
    "mcav-vj/bin/linux-amd64/audioviz-vj",
    "mcav-vj/bin/linux-amd64/python/bin/python3.12",
    "mcav-vj/bin/linux-arm64/audioviz-vj",
    "mcav-vj/bin/linux-arm64/python/bin/python3.12",
    "mcav-vj/release/AudioViz.jar",
    "mcav-vj/release/plugin-config.default.yml",
    "mcav-vj/release/runtime-lock.json",
    "mcav-vj/vj_server/__init__.py",
    "mcav-vj/vj_server/auth.py",
    "mcav-vj/vj_server/cli.py",
    "mcav-vj/vj_server/config.py",
    "mcav-vj/vj_server/patterns.py",
    "mcav-vj/vj_server/vj_server.py",
    "mcav-vj/vj_server/web_gateway.py",
    "mcav-vj/patterns/lib.lua",
    "mcav-vj/patterns/bars.lua",
    "mcav-vj/admin_panel/index.html",
    "mcav-vj/admin_panel/runtime-config.js",
    "mcav-vj/preview_tool/frontend/index.html",
    "mcav-vj/preview_tool/frontend/js/latency-indicator.js",
    "mcav-vj/preview_tool/frontend/js/vendor/three-r128.min.js",
    "mcav-vj/preview_tool/frontend/runtime-config.js",
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
    r"(^|/)(node_modules|\.git|\.venv|__pycache__|tests?)(/|$)|"
    r"\.(pyc|pyo)$|(^|/)[^/]+\.(test|spec)\.[^/]+$",
    re.I,
)
RECORD_HASH_PATTERN = re.compile(r"sha256=([A-Za-z0-9_-]{43})")
NATIVE_SUFFIX_PATTERN = re.compile(r"\.so(?:\.[^/]*)?$|\.(?:pyd|dll|dylib|node)$", re.I)
NATIVE_MAGICS = {
    b"\x7fELF",
    b"MZ",
    b"\xfe\xed\xfa\xce",
    b"\xce\xfa\xed\xfe",
    b"\xfe\xed\xfa\xcf",
    b"\xcf\xfa\xed\xfe",
}
FORBIDDEN_ENTRIES = {
    "mcav-vj/state/dj_auth.json",
    "mcav-vj/state/runtime.env",
    "mcav-vj/state/tls.key",
    "mcav-vj/FIRST_LOGIN.txt",
}
WINDOWS_ZIP_SYSTEM = 0
DOS_DIRECTORY_ATTRIBUTE = 0x10
DOS_DEVICE_ATTRIBUTE = 0x40
DOS_REPARSE_POINT_ATTRIBUTE = 0x400
UNSAFE_DOS_ATTRIBUTES = DOS_DEVICE_ATTRIBUTE | DOS_REPARSE_POINT_ATTRIBUTE


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def validate_canonical_path(path: str, description: str) -> None:
    components = path.split("/")
    if (
        not path
        or "\\" in path
        or path.startswith("/")
        or re.match(r"^[A-Za-z]:", path)
        or any(component in {"", ".", ".."} for component in components)
    ):
        raise ValueError(f"Noncanonical {description}: {path}")


def validate_zip_entry_type(entry: zipfile.ZipInfo) -> bool:
    unix_mode = (entry.external_attr >> 16) & 0xFFFF
    unix_type = stat.S_IFMT(unix_mode)
    dos_attributes = entry.external_attr & 0xFFFF
    path_is_directory = entry.is_dir()

    if dos_attributes & UNSAFE_DOS_ATTRIBUTES:
        raise ValueError(f"Unsafe Windows ZIP entry attributes: {entry.filename}")
    if unix_type not in {0, stat.S_IFREG, stat.S_IFDIR}:
        raise ValueError(f"Non-regular ZIP entry type: {entry.filename}")

    if unix_type == 0:
        if entry.create_system != WINDOWS_ZIP_SYSTEM:
            raise ValueError(f"Ambiguous ZIP entry type metadata: {entry.filename}")
        metadata_is_directory = bool(dos_attributes & DOS_DIRECTORY_ATTRIBUTE)
    else:
        metadata_is_directory = unix_type == stat.S_IFDIR
        if dos_attributes & DOS_DIRECTORY_ATTRIBUTE and not metadata_is_directory:
            raise ValueError(f"ZIP entry type does not match path: {entry.filename}")

    if metadata_is_directory != path_is_directory:
        raise ValueError(f"ZIP entry type does not match path: {entry.filename}")
    return metadata_is_directory


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
    casefolded_paths: dict[str, str] = {}
    for line in payload.decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise ValueError(f"Malformed manifest line: {line}")
        digest, relative_path = match.groups()
        validate_canonical_path(relative_path, "manifest path")
        if relative_path in manifest:
            raise ValueError(f"Duplicate manifest entry: {relative_path}")
        casefolded_path = relative_path.casefold()
        if casefolded_path in casefolded_paths:
            raise ValueError(
                "Case-fold path collision in manifest: "
                f"{casefolded_paths[casefolded_path]} and {relative_path}"
            )
        casefolded_paths[casefolded_path] = relative_path
        manifest[relative_path] = digest
    return manifest


def read_elf_machine(header: bytes, executable_name: str) -> int:
    if len(header) < 20 or header[:4] != b"\x7fELF" or header[4] != 2:
        raise ValueError(f"{executable_name} is not a 64-bit ELF executable")
    if header[5] not in {1, 2}:
        raise ValueError(f"{executable_name} has an unsupported ELF byte order")
    byte_order = "little" if header[5] == 1 else "big"
    return int.from_bytes(header[18:20], byte_order)


def read_native_elf_machine(header: bytes, entry_name: str) -> int:
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise ValueError(f"Native library is not ELF: {entry_name}")
    if header[4] != 2:
        raise ValueError(f"Native library is not 64-bit ELF: {entry_name}")
    if header[5] not in {1, 2}:
        raise ValueError(f"Native ELF has unsupported byte order: {entry_name}")
    byte_order = "little" if header[5] == 1 else "big"
    return int.from_bytes(header[18:20], byte_order)


def parse_record(payload: bytes, record_name: str) -> list[tuple[str, str, str]]:
    try:
        text = payload.decode("utf-8")
        rows = list(csv.reader(io.StringIO(text, newline=""), strict=True))
    except (UnicodeDecodeError, csv.Error) as error:
        raise ValueError(f"Cannot parse installed RECORD {record_name}: {error}") from error
    parsed: list[tuple[str, str, str]] = []
    for row in rows:
        if len(row) != 3:
            raise ValueError(f"Malformed RECORD row in {record_name}: {row}")
        relative_path, digest, size = row
        validate_canonical_path(relative_path, "RECORD path")
        if bool(digest) != bool(size):
            raise ValueError(f"Malformed RECORD row in {record_name}: {row}")
        if digest and RECORD_HASH_PATTERN.fullmatch(digest) is None:
            raise ValueError(f"Malformed RECORD hash in {record_name}: {relative_path}")
        if size and (not size.isascii() or not size.isdecimal()):
            raise ValueError(f"Malformed RECORD size in {record_name}: {relative_path}")
        parsed.append((relative_path, digest, size))
    if not parsed:
        raise ValueError(f"Installed RECORD is empty: {record_name}")
    return parsed


def verify_packaged_runtime_closure(
    release_zip: zipfile.ZipFile,
    entries_by_name: dict[str, zipfile.ZipInfo],
) -> None:
    try:
        lock = validate_lock(json.loads(release_zip.read("mcav-vj/release/runtime-lock.json")))
    except (KeyError, json.JSONDecodeError, UnicodeDecodeError, LockError) as error:
        raise ValueError(f"Invalid packaged runtime lock: {error}") from error

    expected = {
        normalize_package_name(dependency["name"]): dependency
        for dependency in lock["dependencies"]
    }
    for architecture in ("linux-amd64", "linux-arm64"):
        prefix = f"mcav-vj/bin/{architecture}/python/lib/python3.12/site-packages/"
        dist_info_directories: set[str] = set()
        for entry_name in entries_by_name:
            if not entry_name.startswith(prefix):
                continue
            relative_path = entry_name.removeprefix(prefix)
            for index, component in enumerate(relative_path.split("/")):
                if not component.casefold().endswith(".dist-info"):
                    continue
                if index != 0 or not component.endswith(".dist-info"):
                    raise ValueError(
                        f"{architecture} has noncanonical installed dist-info: {relative_path}"
                    )
                dist_info_directories.add(component)

        installed: dict[str, tuple[str, str]] = {}
        for directory in sorted(dist_info_directories):
            metadata_name = f"{prefix}{directory}/METADATA"
            metadata_entry = entries_by_name.get(metadata_name)
            if metadata_entry is None:
                raise ValueError(
                    f"{architecture} installed dist-info is missing METADATA: {directory}"
                )
            metadata = BytesParser().parsebytes(release_zip.read(metadata_entry))
            name = metadata.get("Name")
            version = metadata.get("Version")
            if not name or not version:
                raise ValueError(
                    f"{architecture} installed metadata is incomplete: {metadata_name}"
                )
            normalized_name = normalize_package_name(name)
            if normalized_name in installed:
                raise ValueError(f"{architecture} duplicate installed dependency: {name}")
            installed[normalized_name] = (version, metadata_name)

        missing = sorted(set(expected) - set(installed))
        extra = sorted(set(installed) - set(expected))
        if missing:
            raise ValueError(f"{architecture} missing installed dependencies: {', '.join(missing)}")
        if extra:
            raise ValueError(f"{architecture} extra installed dependencies: {', '.join(extra)}")
        for normalized_name, dependency in expected.items():
            installed_version = installed[normalized_name][0]
            if installed_version != dependency["version"]:
                raise ValueError(
                    f"{architecture} installed {dependency['name']} version "
                    f"{installed_version} does not match {dependency['version']}"
                )

        ownership: dict[str, str] = {}
        for directory in sorted(dist_info_directories):
            record_name = f"{prefix}{directory}/RECORD"
            record_entry = entries_by_name.get(record_name)
            if record_entry is None:
                raise ValueError(
                    f"{architecture} installed dist-info is missing RECORD: {directory}"
                )
            for relative_path, digest, size in parse_record(
                release_zip.read(record_entry), record_name
            ):
                previous_owner = ownership.get(relative_path)
                if previous_owner is not None:
                    raise ValueError(
                        "Ambiguous RECORD ownership for "
                        f"{relative_path}: {previous_owner} and {record_name}"
                    )
                ownership[relative_path] = record_name
                installed_name = f"{prefix}{relative_path}"
                installed_entry = entries_by_name.get(installed_name)
                if installed_entry is None:
                    raise ValueError(f"RECORD file is missing: {relative_path}")
                installed_payload = release_zip.read(installed_entry)
                if size and len(installed_payload) != int(size):
                    raise ValueError(
                        f"RECORD size mismatch for {relative_path}: "
                        f"expected {size}, got {len(installed_payload)}"
                    )
                if digest:
                    encoded_digest = base64.urlsafe_b64encode(
                        hashlib.sha256(installed_payload).digest()
                    ).rstrip(b"=")
                    if digest.removeprefix("sha256=") != encoded_digest.decode("ascii"):
                        raise ValueError(f"RECORD SHA-256 mismatch for {relative_path}")

        actual_site_packages = {
            entry_name.removeprefix(prefix): entry
            for entry_name, entry in entries_by_name.items()
            if entry_name.startswith(prefix)
        }
        unowned = sorted(set(actual_site_packages) - set(ownership))
        if unowned:
            raise ValueError(f"Unowned site-packages file: {unowned[0]}")

        expected_machine = 62 if architecture == "linux-amd64" else 183
        for relative_path, entry in actual_site_packages.items():
            with release_zip.open(entry) as installed_file:
                header = installed_file.read(20)
            extension_marks_native = NATIVE_SUFFIX_PATTERN.search(relative_path) is not None
            magic_marks_native = header[:4] in NATIVE_MAGICS or header[:2] == b"MZ"
            if not extension_marks_native and not magic_marks_native:
                continue
            actual_machine = read_native_elf_machine(header, f"{prefix}{relative_path}")
            if actual_machine != expected_machine:
                raise ValueError(
                    f"{architecture} native ELF machine {actual_machine}; "
                    f"expected {expected_machine}: {relative_path}"
                )


def verify_archive(archive_path: Path) -> int:
    with zipfile.ZipFile(archive_path) as release_zip:
        entries = release_zip.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("Release archive contains duplicate ZIP entries")
        casefolded_names: dict[str, str] = {}
        for entry in entries:
            validate_canonical_path(entry.filename, "ZIP entry")
            if validate_zip_entry_type(entry):
                raise ValueError(f"Noncanonical ZIP entry: {entry.filename}")
            casefolded_name = entry.filename.casefold()
            if casefolded_name in casefolded_names:
                raise ValueError(
                    "Case-fold path collision in ZIP: "
                    f"{casefolded_names[casefolded_name]} and {entry.filename}"
                )
            casefolded_names[casefolded_name] = entry.filename
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
        verify_packaged_runtime_closure(release_zip, entries_by_name)
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
