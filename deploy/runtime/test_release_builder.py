from __future__ import annotations

import hashlib
import json
import stat
import zipfile
from pathlib import Path
from typing import Any, Callable

import pytest
from build_release_runtime import (
    ReleaseBuildError,
    detect_native_target,
    load_runtime_lock,
    release_archive_name,
    require_native_target,
)
from verify_release_runtime import ReleaseArchiveError, verify_release_archive

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
LOCK_PATH = SCRIPT_DIRECTORY / "runtime-lock.json"
SOURCE_DATE_EPOCH = 315532800
ARCHIVE_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
ENTRYPOINT = "python/bin/python3.12"
LOCK_VALUE = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
LICENSE_MANIFEST = {
    "dependencies": [
        {
            "license_expression": dependency["license_expression"],
            "name": dependency["name"],
            "version": dependency["version"],
        }
        for dependency in sorted(
            LOCK_VALUE["dependencies"], key=lambda item: item["name"].casefold()
        )
    ],
    "schema_version": 1,
}
LICENSE_MANIFEST_BYTES = (
    json.dumps(LICENSE_MANIFEST, separators=(",", ":"), sort_keys=True) + "\n"
).encode()
REQUIRED_FILES = {
    "LICENSE": b"MCAV license\n",
    "python/LICENSE.txt": b"Python license\n",
    "THIRD_PARTY_LICENSES.json": LICENSE_MANIFEST_BYTES,
    ENTRYPOINT: b"python executable",
    "vj_server/cli.py": b"def main(): pass\n",
    "patterns/lib.lua": b"return {}\n",
    "admin_panel/index.html": b"<!doctype html>\n",
    "preview_tool/frontend/index.html": b"<!doctype html>\n",
}


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def lock_fixture(tmp_path: Path, update: Callable[[dict[str, Any]], None]) -> Path:
    value = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    update(value)
    path = tmp_path / "runtime-lock.json"
    write_json(path, value)
    return path


def write_archive(
    path: Path,
    *,
    files: dict[str, bytes] | None = None,
    declared_files: dict[str, bytes] | None = None,
    timestamp: tuple[int, int, int, int, int, int] = ARCHIVE_TIMESTAMP,
    entrypoint_mode: int = 0o700,
    symlink: str | None = None,
    reverse_members: bool = False,
) -> Path:
    payloads = dict(REQUIRED_FILES if files is None else files)
    declared = dict(payloads if declared_files is None else declared_files)
    manifest = {
        "files": [
            {
                "executable": name == ENTRYPOINT,
                "path": name,
                "sha256": sha256(payload),
                "size": len(payload),
            }
            for name, payload in sorted(declared.items())
        ],
        "schema_version": 1,
    }
    manifest_bytes = (json.dumps(manifest, separators=(",", ":"), sort_keys=True) + "\n").encode()
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        members = sorted(payloads.items(), reverse=reverse_members)
        for name, payload in members:
            info = zipfile.ZipInfo(name, timestamp)
            info.create_system = 3
            mode = entrypoint_mode if name == ENTRYPOINT else 0o600
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, payload)
        info = zipfile.ZipInfo("files.json", timestamp)
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | 0o600) << 16
        archive.writestr(info, manifest_bytes)
        if symlink is not None:
            link = zipfile.ZipInfo(symlink, timestamp)
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(link, ENTRYPOINT)
    return path


def test_runtime_lock_is_strict_complete_and_size_pinned() -> None:
    lock = load_runtime_lock(LOCK_PATH)

    assert lock["source_date_epoch"] == SOURCE_DATE_EPOCH
    assert set(lock["runtimes"]) == {
        "linux-x86_64",
        "linux-aarch64",
        "windows-x86_64",
    }
    assert all(runtime["archive_size"] > 20_000_000 for runtime in lock["runtimes"].values())


def test_runtime_lock_rejects_unknown_fields(tmp_path: Path) -> None:
    path = lock_fixture(tmp_path, lambda value: value.update(unknown=True))

    with pytest.raises(ReleaseBuildError, match="unknown fields"):
        load_runtime_lock(path)


@pytest.mark.parametrize("field", ("url", "sha256", "archive_size"))
def test_runtime_lock_requires_pinned_download_metadata(tmp_path: Path, field: str) -> None:
    path = lock_fixture(tmp_path, lambda value: value["runtimes"]["linux-x86_64"].pop(field))

    with pytest.raises(ReleaseBuildError, match=field):
        load_runtime_lock(path)


def test_runtime_lock_rejects_incomplete_platform_wheels(tmp_path: Path) -> None:
    def remove_windows_wheel(value: dict[str, Any]) -> None:
        dependency = next(item for item in value["dependencies"] if item["name"] == "aiohttp")
        dependency["wheels"].pop("windows-x86_64")

    path = lock_fixture(tmp_path, remove_windows_wheel)

    with pytest.raises(ReleaseBuildError, match="aiohttp.*windows-x86_64"):
        load_runtime_lock(path)


def test_runtime_lock_rejects_platform_tag_mismatch(tmp_path: Path) -> None:
    def replace_tag(value: dict[str, Any]) -> None:
        dependency = next(item for item in value["dependencies"] if item["name"] == "aiohttp")
        wheel = dependency["wheels"]["windows-x86_64"]
        wheel["filename"] = "aiohttp-3.14.3-cp312-cp312-manylinux_2_28_x86_64.whl"
        wheel["url"] = wheel["url"].rsplit("/", 1)[0] + "/" + wheel["filename"]

    path = lock_fixture(tmp_path, replace_tag)

    with pytest.raises(ReleaseBuildError, match="platform tag"):
        load_runtime_lock(path)


def test_runtime_lock_rejects_runtime_for_wrong_architecture(tmp_path: Path) -> None:
    def replace_runtime(value: dict[str, Any]) -> None:
        value["runtimes"]["linux-aarch64"]["url"] = value["runtimes"]["linux-x86_64"]["url"]

    path = lock_fixture(tmp_path, replace_runtime)

    with pytest.raises(ReleaseBuildError, match="target artifact"):
        load_runtime_lock(path)


def test_runtime_lock_rejects_wheel_for_wrong_distribution(tmp_path: Path) -> None:
    def replace_distribution(value: dict[str, Any]) -> None:
        dependency = next(item for item in value["dependencies"] if item["name"] == "aiohttp")
        wheel = dependency["wheels"]["windows-x86_64"]
        wheel["filename"] = "multidict-3.14.3-cp312-cp312-win_amd64.whl"
        wheel["url"] = wheel["url"].rsplit("/", 1)[0] + "/" + wheel["filename"]

    path = lock_fixture(tmp_path, replace_distribution)

    with pytest.raises(ReleaseBuildError, match="distribution or version"):
        load_runtime_lock(path)


@pytest.mark.parametrize("field", ("url", "size"))
def test_runtime_lock_requires_bounded_wheel_source(tmp_path: Path, field: str) -> None:
    def remove_field(value: dict[str, Any]) -> None:
        dependency = next(item for item in value["dependencies"] if item["name"] == "aiohttp")
        dependency["wheels"]["windows-x86_64"].pop(field)

    path = lock_fixture(tmp_path, remove_field)

    with pytest.raises(ReleaseBuildError, match=field):
        load_runtime_lock(path)


def test_runtime_lock_requires_license_evidence(tmp_path: Path) -> None:
    def remove_license(value: dict[str, Any]) -> None:
        value["dependencies"][0].pop("license_expression")

    path = lock_fixture(tmp_path, remove_license)

    with pytest.raises(ReleaseBuildError, match="license_expression"):
        load_runtime_lock(path)


@pytest.mark.parametrize(
    ("system", "machine", "expected"),
    (
        ("Linux", "x86_64", "linux-x86_64"),
        ("Linux", "aarch64", "linux-aarch64"),
        ("Windows", "AMD64", "windows-x86_64"),
    ),
)
def test_native_target_detection(system: str, machine: str, expected: str) -> None:
    assert detect_native_target(system, machine) == expected


def test_native_target_enforcement_rejects_cross_build() -> None:
    with pytest.raises(ReleaseBuildError, match="native target"):
        require_native_target("linux-aarch64", detected="linux-x86_64")


@pytest.mark.parametrize(
    ("version", "target", "expected"),
    (
        (
            "1.2.0",
            "linux-x86_64",
            "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        ),
        (
            "1.2.0-rc.3",
            "windows-x86_64",
            "mcav-vj-runtime-1.2.0-rc.3-windows-x86_64.zip",
        ),
    ),
)
def test_release_archive_name(version: str, target: str, expected: str) -> None:
    assert release_archive_name(version, target) == expected


def test_release_archive_verifies_inventory_modes_and_required_files(tmp_path: Path) -> None:
    archive = write_archive(tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip")

    result = verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)

    assert result.entrypoint == ENTRYPOINT
    assert result.file_count == len(REQUIRED_FILES)
    assert result.release_version == "1.2.0"


def test_release_archive_refuses_untrusted_executable_smoke(tmp_path: Path) -> None:
    archive = write_archive(tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip")

    with pytest.raises(ReleaseArchiveError, match="trusted SHA-256"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH, smoke=True)


def test_release_archive_rejects_undeclared_member(tmp_path: Path) -> None:
    files = dict(REQUIRED_FILES)
    files["undeclared.txt"] = b"not in files.json"
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        files=files,
        declared_files=REQUIRED_FILES,
    )

    with pytest.raises(ReleaseArchiveError, match="inventory"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


@pytest.mark.parametrize(
    "unsafe_name",
    (
        "D:/escape",
        "file:stream",
        "CON",
        "folder/NUL.txt",
        "folder/trailing. ",
        "folder/control\x01.txt",
    ),
)
def test_release_archive_rejects_windows_unsafe_members(tmp_path: Path, unsafe_name: str) -> None:
    files = dict(REQUIRED_FILES)
    files[unsafe_name] = b"unsafe"
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        files=files,
    )

    with pytest.raises(ReleaseArchiveError, match="unsafe"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


def test_release_archive_rejects_symlink(tmp_path: Path) -> None:
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        symlink="python/bin/python3",
    )

    with pytest.raises(ReleaseArchiveError, match="regular file"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


def test_release_archive_rejects_wrong_entrypoint_mode(tmp_path: Path) -> None:
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        entrypoint_mode=0o600,
    )

    with pytest.raises(ReleaseArchiveError, match="mode"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


def test_release_archive_rejects_non_normalized_timestamp(tmp_path: Path) -> None:
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        timestamp=(2026, 8, 31, 12, 0, 0),
    )

    with pytest.raises(ReleaseArchiveError, match="timestamp"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


def test_release_archive_rejects_non_canonical_member_order(tmp_path: Path) -> None:
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        reverse_members=True,
    )

    with pytest.raises(ReleaseArchiveError, match="member order"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


def test_release_archive_rejects_tampered_payload(tmp_path: Path) -> None:
    files = dict(REQUIRED_FILES)
    files["vj_server/cli.py"] = b"def main(): fail\n"
    archive = write_archive(
        tmp_path / "mcav-vj-runtime-1.2.0-linux-x86_64.zip",
        files=files,
        declared_files=REQUIRED_FILES,
    )

    with pytest.raises(ReleaseArchiveError, match="digest"):
        verify_release_archive(archive, "linux-x86_64", lock_path=LOCK_PATH)


def test_fixture_archives_are_byte_for_byte_reproducible(tmp_path: Path) -> None:
    first = write_archive(tmp_path / "first.zip")
    second = write_archive(tmp_path / "second.zip")

    assert first.read_bytes() == second.read_bytes()
