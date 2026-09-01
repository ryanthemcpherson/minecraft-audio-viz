#!/usr/bin/env python3
"""Independently verify and smoke-test an MCAV VJ runtime release ZIP."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import subprocess  # nosec B404 -- only a verified native runtime executable is invoked.
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any

from build_release_runtime import (
    LOCK_PATH,
    REPOSITORY_ROOT,
    ReleaseBuildError,
    load_runtime_lock,
    release_archive_name,
    require_native_target,
)

MAX_ARCHIVE_SIZE = 500 * 1024 * 1024
MAX_UNCOMPRESSED_SIZE = 2 * 1024 * 1024 * 1024
MAX_FILE_SIZE = 512 * 1024 * 1024
MAX_FILES = 50_000
MAX_COMPRESSION_RATIO = 100
MANIFEST_FIELDS = {"schema_version", "files"}
FILE_FIELDS = {"executable", "path", "sha256", "size"}
REQUIRED_FILES = {
    "LICENSE",
    "THIRD_PARTY_LICENSES.json",
    "python/LICENSE.txt",
    "vj_server/cli.py",
    "patterns/lib.lua",
    "admin_panel/index.html",
    "preview_tool/frontend/index.html",
}
WINDOWS_RESERVED_NAMES = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{index}" for index in range(1, 10)),
    *(f"LPT{index}" for index in range(1, 10)),
}
SMOKE_IMPORTS = "aiohttp,bcrypt,cryptography,lupa,msgspec,numpy,websockets"


class ReleaseArchiveError(ValueError):
    """Raised when a release archive violates the immutable archive contract."""


@dataclass(frozen=True)
class ReleaseArchiveResult:
    entrypoint: str
    file_count: int
    release_version: str
    sha256: str


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ReleaseArchiveError(f"duplicate JSON field: {key}")
        value[key] = item
    return value


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")


def _archive_version(path: Path, target: str, version: str | None) -> str:
    prefix = "mcav-vj-runtime-"
    suffix = f"-{target}.zip"
    if not path.name.startswith(prefix) or not path.name.endswith(suffix):
        raise ReleaseArchiveError("release archive filename does not match its target")
    parsed = path.name[len(prefix) : -len(suffix)]
    if version is not None and parsed != version:
        raise ReleaseArchiveError("release archive filename does not match requested version")
    try:
        if release_archive_name(parsed, target) != path.name:
            raise ReleaseArchiveError("release archive filename is not canonical")
    except ReleaseBuildError as error:
        raise ReleaseArchiveError(str(error)) from error
    release_scripts = REPOSITORY_ROOT / "scripts" / "release"
    if str(release_scripts) not in sys.path:
        sys.path.insert(0, str(release_scripts))
    from verify_versions import ContractError, verify_repository

    try:
        verify_repository(REPOSITORY_ROOT, parsed)
    except ContractError as error:
        raise ReleaseArchiveError(f"release version contract failed: {error}") from error
    return parsed


def _safe_name(name: str) -> None:
    path = PurePosixPath(name)
    windows_path = PureWindowsPath(name)
    if (
        not name
        or len(name) > 500
        or "\\" in name
        or name.startswith("/")
        or windows_path.drive
        or path.as_posix() != name
        or any(part in {"", ".", ".."} for part in path.parts)
        or any(
            not part
            or part[-1] in {".", " "}
            or any(ord(character) < 32 or character in '<>:"|?*' for character in part)
            or part.split(".", 1)[0].upper() in WINDOWS_RESERVED_NAMES
            for part in path.parts
        )
    ):
        raise ReleaseArchiveError(f"archive member path is unsafe: {name!r}")


def _expected_timestamp(source_date_epoch: int) -> tuple[int, int, int, int, int, int]:
    value = datetime.fromtimestamp(source_date_epoch, timezone.utc)
    return (value.year, value.month, value.day, value.hour, value.minute, value.second)


def _validate_manifest(payload: bytes) -> tuple[dict[str, dict[str, Any]], bytes]:
    if len(payload) > 20 * 1024 * 1024:
        raise ReleaseArchiveError("files.json is too large")
    try:
        value = json.loads(payload, object_pairs_hook=_unique_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseArchiveError(f"files.json is invalid: {error}") from error
    if not isinstance(value, dict) or set(value) != MANIFEST_FIELDS:
        raise ReleaseArchiveError("files.json fields are invalid")
    if value["schema_version"] != 1:
        raise ReleaseArchiveError("files.json schema_version must be 1")
    files = value["files"]
    if not isinstance(files, list) or not files or len(files) > MAX_FILES:
        raise ReleaseArchiveError("files.json inventory is empty or too large")
    inventory: dict[str, dict[str, Any]] = {}
    folded: set[str] = set()
    previous = ""
    for item in files:
        if not isinstance(item, dict) or set(item) != FILE_FIELDS:
            raise ReleaseArchiveError("files.json inventory entry fields are invalid")
        path = item["path"]
        if not isinstance(path, str):
            raise ReleaseArchiveError("files.json inventory path must be a string")
        _safe_name(path)
        if path == "files.json" or path <= previous or path.casefold() in folded:
            raise ReleaseArchiveError("files.json inventory paths are not unique and sorted")
        previous = path
        folded.add(path.casefold())
        if not isinstance(item["executable"], bool):
            raise ReleaseArchiveError(f"files.json executable flag is invalid: {path}")
        if (
            isinstance(item["size"], bool)
            or not isinstance(item["size"], int)
            or not 0 <= item["size"] <= MAX_FILE_SIZE
        ):
            raise ReleaseArchiveError(f"files.json size is invalid: {path}")
        digest = item["sha256"]
        if (
            not isinstance(digest, str)
            or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
        ):
            raise ReleaseArchiveError(f"files.json digest is invalid: {path}")
        inventory[path] = item
    canonical = _canonical_json(value)
    if payload != canonical:
        raise ReleaseArchiveError("files.json is not canonical JSON")
    return inventory, canonical


def _validate_license_manifest(payload: bytes, lock: dict[str, Any]) -> None:
    expected = {
        "dependencies": [
            {
                "license_expression": dependency["license_expression"],
                "name": dependency["name"],
                "version": dependency["version"],
            }
            for dependency in sorted(lock["dependencies"], key=lambda item: item["name"].casefold())
        ],
        "schema_version": 1,
    }
    try:
        observed = json.loads(payload, object_pairs_hook=_unique_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseArchiveError(f"THIRD_PARTY_LICENSES.json is invalid: {error}") from error
    if observed != expected or payload != _canonical_json(expected):
        raise ReleaseArchiveError("third-party license inventory does not match the runtime lock")


def _extract_verified(archive: zipfile.ZipFile, destination: Path) -> None:
    destination_root = destination.resolve()
    for info in archive.infolist():
        target = destination.joinpath(*PurePosixPath(info.filename).parts)
        try:
            target.resolve().relative_to(destination_root)
        except ValueError as error:
            raise ReleaseArchiveError(
                f"archive member escapes the extraction root: {info.filename}"
            ) from error
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(info) as source, target.open("wb") as output:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        mode = (info.external_attr >> 16) & 0o777
        target.chmod(mode)


def _smoke_archive(path: Path, target: str, entrypoint: str, release_version: str) -> None:
    try:
        require_native_target(target)
    except ReleaseBuildError as error:
        raise ReleaseArchiveError(str(error)) from error
    with tempfile.TemporaryDirectory(prefix="mcav-release-verify-") as temporary_value:
        root = Path(temporary_value)
        with zipfile.ZipFile(path) as archive:
            _extract_verified(archive, root)
        interpreter = root.joinpath(*PurePosixPath(entrypoint).parts)
        allowed_environment = {
            "COMSPEC",
            "LANG",
            "LC_ALL",
            "NO_COLOR",
            "PATHEXT",
            "SYSTEMROOT",
            "TEMP",
            "TMP",
            "TMPDIR",
            "TZ",
            "USERPROFILE",
            "WINDIR",
        }
        environment = {
            key: value for key, value in os.environ.items() if key.upper() in allowed_environment
        }
        environment.update(
            {
                "OPENBLAS_NUM_THREADS": "1",
                "OMP_NUM_THREADS": "1",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
        )
        import_script = ";".join(f"import {name}" for name in SMOKE_IMPORTS.split(","))
        commands = (
            [str(interpreter), "-I", "-c", import_script],
            [str(interpreter), "-m", "vj_server.cli", "--help"],
            [
                str(interpreter),
                str(Path(__file__).with_name("smoke_release_runtime.py")),
                "--runtime-root",
                str(root),
                "--entrypoint",
                str(interpreter),
                "--release-version",
                release_version,
            ],
        )
        for index, command in enumerate(commands):
            result = subprocess.run(  # nosec B603 -- executable and payload were fully verified.
                command,
                cwd=root,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=210 if index == 2 else 60,
                check=False,
            )
            if result.returncode != 0:
                raise ReleaseArchiveError(
                    f"native runtime smoke failed with exit {result.returncode}: "
                    + result.stdout[-2000:]
                )


def verify_release_archive(
    path: Path,
    target: str,
    *,
    lock_path: Path = LOCK_PATH,
    smoke: bool = False,
    expected_sha256: str | None = None,
    trusted_local_build: bool = False,
    version: str | None = None,
) -> ReleaseArchiveResult:
    """Verify the complete archive structure, inventory, payloads, and native imports."""
    path = path.resolve()
    if not path.is_file():
        raise ReleaseArchiveError(f"release archive does not exist: {path}")
    if path.stat().st_size > MAX_ARCHIVE_SIZE:
        raise ReleaseArchiveError("release archive exceeds the size limit")
    archive_sha256 = _sha256_file(path)
    if expected_sha256 is not None and (
        len(expected_sha256) != 64
        or any(character not in "0123456789abcdef" for character in expected_sha256)
    ):
        raise ReleaseArchiveError("expected SHA-256 must be a lowercase digest")
    if expected_sha256 is not None and archive_sha256 != expected_sha256:
        raise ReleaseArchiveError("release archive does not match the trusted SHA-256")
    if smoke and expected_sha256 is None and not trusted_local_build:
        raise ReleaseArchiveError(
            "executable smoke requires a trusted SHA-256 or the internal local-build gate"
        )
    try:
        lock = load_runtime_lock(lock_path)
    except ReleaseBuildError as error:
        raise ReleaseArchiveError(f"runtime lock failed validation: {error}") from error
    if target not in lock["runtimes"]:
        raise ReleaseArchiveError(f"unsupported release target: {target}")
    release_version = _archive_version(path, target, version)
    expected_entrypoint = lock["runtimes"][target]["entrypoint"]
    expected_timestamp = _expected_timestamp(lock["source_date_epoch"])
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_FILES + 1:
                raise ReleaseArchiveError("archive file count is invalid")
            if archive.comment:
                raise ReleaseArchiveError("archive comment is not allowed")
            names: set[str] = set()
            folded: set[str] = set()
            total_size = 0
            for info in entries:
                _safe_name(info.filename)
                if info.filename in names or info.filename.casefold() in folded:
                    raise ReleaseArchiveError(
                        "archive contains duplicate or case-colliding members"
                    )
                names.add(info.filename)
                folded.add(info.filename.casefold())
                if info.flag_bits & 0x1:
                    raise ReleaseArchiveError("archive contains an encrypted member")
                if info.extra or info.comment:
                    raise ReleaseArchiveError(
                        f"archive member metadata is not canonical: {info.filename}"
                    )
                if info.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
                    raise ReleaseArchiveError("archive contains an unsupported compression method")
                mode = info.external_attr >> 16
                if info.create_system != 3 or stat.S_IFMT(mode) != stat.S_IFREG:
                    raise ReleaseArchiveError(
                        f"archive member is not a regular file: {info.filename}"
                    )
                expected_mode = 0o700 if info.filename == expected_entrypoint else 0o600
                if stat.S_IMODE(mode) != expected_mode:
                    raise ReleaseArchiveError(f"archive member mode is invalid: {info.filename}")
                if info.date_time != expected_timestamp:
                    raise ReleaseArchiveError(
                        f"archive member timestamp is invalid: {info.filename}"
                    )
                if info.file_size > MAX_FILE_SIZE:
                    raise ReleaseArchiveError(f"archive member is too large: {info.filename}")
                if info.file_size > max(1, info.compress_size) * MAX_COMPRESSION_RATIO:
                    raise ReleaseArchiveError(
                        f"archive member compression ratio is unsafe: {info.filename}"
                    )
                total_size += info.file_size
            if total_size > MAX_UNCOMPRESSED_SIZE:
                raise ReleaseArchiveError("archive uncompressed size exceeds the limit")
            if "files.json" not in names:
                raise ReleaseArchiveError("archive is missing files.json")
            manifest_payload = archive.read("files.json")
            inventory, _ = _validate_manifest(manifest_payload)
            if set(inventory) != names - {"files.json"}:
                raise ReleaseArchiveError("archive inventory does not match ZIP members")
            if [item.filename for item in entries] != [*inventory, "files.json"]:
                raise ReleaseArchiveError("archive member order is not canonical")
            missing = sorted((REQUIRED_FILES | {expected_entrypoint}) - set(inventory))
            if missing:
                raise ReleaseArchiveError(
                    "archive is missing required files: " + ", ".join(missing)
                )
            executables = [name for name, item in inventory.items() if item["executable"]]
            if executables != [expected_entrypoint]:
                raise ReleaseArchiveError(
                    "archive inventory must declare exactly the locked entrypoint"
                )
            for name, item in inventory.items():
                payload = archive.read(name)
                if len(payload) != item["size"]:
                    raise ReleaseArchiveError(f"archive payload size mismatch: {name}")
                if _sha256(payload) != item["sha256"]:
                    raise ReleaseArchiveError(f"archive payload digest mismatch: {name}")
            _validate_license_manifest(archive.read("THIRD_PARTY_LICENSES.json"), lock)
            bad_member = archive.testzip()
            if bad_member is not None:
                raise ReleaseArchiveError(f"archive CRC check failed: {bad_member}")
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise ReleaseArchiveError(f"unable to verify release archive: {error}") from error
    if smoke:
        _smoke_archive(path, target, expected_entrypoint, release_version)
    return ReleaseArchiveResult(
        entrypoint=expected_entrypoint,
        file_count=len(inventory),
        release_version=release_version,
        sha256=archive_sha256,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--target", required=True, choices=tuple(load_runtime_lock()["runtimes"]))
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--expected-sha256")
    arguments = parser.parse_args()
    try:
        result = verify_release_archive(
            arguments.archive,
            arguments.target,
            expected_sha256=arguments.expected_sha256,
            smoke=arguments.smoke,
        )
    except (OSError, ReleaseArchiveError) as error:
        parser.exit(1, f"release runtime verification failed: {error}\n")
    print(
        json.dumps(
            {
                "entrypoint": result.entrypoint,
                "file_count": result.file_count,
                "release_version": result.release_version,
                "sha256": result.sha256,
                "status": "verified",
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
