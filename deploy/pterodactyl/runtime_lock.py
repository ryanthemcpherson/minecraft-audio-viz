#!/usr/bin/env python3
"""Validate and consume the portable runtime's exact wheel lock."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
import zipfile
from email.parser import BytesParser
from pathlib import Path
from typing import Any

ARCHITECTURES = {
    "linux-amd64": {"elf_machine": 62},
    "linux-arm64": {"elf_machine": 183},
}
HASH_PATTERN = re.compile(r"[0-9a-f]{64}")


class LockError(ValueError):
    """Raised when a runtime lock or its installed artifacts are invalid."""


def normalize_package_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _require_string(value: Any, description: str) -> str:
    if not isinstance(value, str) or not value:
        raise LockError(f"{description} must be a non-empty string")
    return value


def validate_lock(lock: Any) -> dict[str, Any]:
    if not isinstance(lock, dict) or lock.get("schema_version") != 1:
        raise LockError("runtime lock schema_version must be 1")
    runtimes = lock.get("runtimes")
    if not isinstance(runtimes, dict) or set(runtimes) != set(ARCHITECTURES):
        raise LockError("runtime lock must define exactly linux-amd64 and linux-arm64")
    for architecture, runtime in runtimes.items():
        if not isinstance(runtime, dict):
            raise LockError(f"runtime {architecture} must be an object")
        platforms = runtime.get("pip_platforms")
        if (
            not isinstance(platforms, list)
            or not platforms
            or any(not isinstance(platform, str) or not platform for platform in platforms)
            or len(platforms) != len(set(platforms))
        ):
            raise LockError(f"runtime {architecture} pip_platforms must be unique strings")
    dependencies = lock.get("dependencies")
    if not isinstance(dependencies, list) or not dependencies:
        raise LockError("runtime lock dependencies must be a non-empty list")

    seen_packages: set[str] = set()
    for index, dependency in enumerate(dependencies):
        if not isinstance(dependency, dict):
            raise LockError(f"dependency {index} must be an object")
        name = _require_string(dependency.get("name"), f"dependency {index} name")
        version = _require_string(dependency.get("version"), f"dependency {name} version")
        normalized_name = normalize_package_name(name)
        if normalized_name in seen_packages:
            raise LockError(f"duplicate locked dependency: {name}")
        seen_packages.add(normalized_name)
        wheels = dependency.get("wheels")
        if not isinstance(wheels, dict) or set(wheels) != set(ARCHITECTURES):
            raise LockError(f"dependency {name} {version} must lock exactly both architectures")
        for architecture, wheel in wheels.items():
            if not isinstance(wheel, dict) or set(wheel) != {"filename", "sha256"}:
                raise LockError(
                    f"dependency {name} {architecture} wheel must contain filename and sha256"
                )
            filename = _require_string(
                wheel.get("filename"), f"dependency {name} {architecture} filename"
            )
            digest = _require_string(
                wheel.get("sha256"), f"dependency {name} {architecture} sha256"
            )
            if Path(filename).name != filename or not filename.endswith(".whl"):
                raise LockError(f"dependency {name} {architecture} has invalid wheel filename")
            if HASH_PATTERN.fullmatch(digest) is None:
                raise LockError(f"dependency {name} {architecture} has invalid SHA-256")
    return lock


def load_lock(path: Path) -> dict[str, Any]:
    try:
        lock = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise LockError(f"cannot read runtime lock {path}: {error}") from error
    return validate_lock(lock)


def _wheel_metadata(wheel_path: Path) -> tuple[str, str, tuple[str, ...]]:
    try:
        with zipfile.ZipFile(wheel_path) as wheel:
            metadata_names = [
                name for name in wheel.namelist() if name.endswith(".dist-info/METADATA")
            ]
            wheel_names = [name for name in wheel.namelist() if name.endswith(".dist-info/WHEEL")]
            if len(metadata_names) != 1 or len(wheel_names) != 1:
                raise LockError(
                    f"wheel {wheel_path.name} must contain exactly one METADATA and WHEEL"
                )
            metadata = BytesParser().parsebytes(wheel.read(metadata_names[0]))
            wheel_metadata = BytesParser().parsebytes(wheel.read(wheel_names[0]))
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise LockError(f"cannot inspect wheel {wheel_path.name}: {error}") from error
    name = metadata.get("Name")
    version = metadata.get("Version")
    tags = tuple(wheel_metadata.get_all("Tag", []))
    if not name or not version or not tags:
        raise LockError(f"wheel {wheel_path.name} has incomplete metadata")
    return name, version, tags


def _python_abi_compatible(python_tag: str, abi_tag: str) -> bool:
    if python_tag == "cp312" and abi_tag in {"cp312", "abi3", "none"}:
        return True
    stable_abi = re.fullmatch(r"cp3([0-9]+)", python_tag)
    if stable_abi and abi_tag == "abi3":
        return int(stable_abi.group(1)) <= 12
    return python_tag in {"py3", "py312"} and abi_tag == "none"


def _wheel_tag_is_compatible(tag: str, platforms: set[str]) -> bool:
    parts = tag.split("-")
    if len(parts) != 3:
        return False
    python_tags, abi_tags, platform_tags = (part.split(".") for part in parts)
    compatible_python_abi = any(
        _python_abi_compatible(python_tag, abi_tag)
        for python_tag in python_tags
        for abi_tag in abi_tags
    )
    if not compatible_python_abi:
        return False
    compatible_platforms = {"any", *platforms}
    if not any(platform in compatible_platforms for platform in platform_tags):
        return False
    if "any" in platform_tags and not any(abi_tag == "none" for abi_tag in abi_tags):
        return False
    return True


def _wheel_supports_architecture(
    wheel_path: Path,
    tags: tuple[str, ...],
    platforms: set[str],
) -> bool:
    filename_parts = wheel_path.name.removesuffix(".whl").rsplit("-", 3)
    if len(filename_parts) != 4:
        return False
    filename_tag = "-".join(filename_parts[1:])
    return _wheel_tag_is_compatible(filename_tag, platforms) and any(
        _wheel_tag_is_compatible(tag, platforms) for tag in tags
    )


def verify_wheelhouse(lock_path: Path, architecture: str, wheelhouse: Path) -> list[Path]:
    lock = load_lock(lock_path)
    if architecture not in ARCHITECTURES:
        raise LockError(f"unsupported architecture: {architecture}")
    if not wheelhouse.is_dir():
        raise LockError(f"wheelhouse does not exist: {wheelhouse}")
    expected = {
        dependency["wheels"][architecture]["filename"]: dependency
        for dependency in lock["dependencies"]
    }
    actual = {path.name: path for path in wheelhouse.glob("*.whl") if path.is_file()}
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    if missing:
        raise LockError(f"missing locked wheels: {', '.join(missing)}")
    if extra:
        raise LockError(f"extra wheels: {', '.join(extra)}")

    verified: list[Path] = []
    platforms = set(lock["runtimes"][architecture]["pip_platforms"])
    for dependency in lock["dependencies"]:
        wheel_record = dependency["wheels"][architecture]
        wheel_path = actual[wheel_record["filename"]]
        actual_sha = hashlib.sha256(wheel_path.read_bytes()).hexdigest()
        if actual_sha != wheel_record["sha256"]:
            raise LockError(
                f"SHA-256 mismatch for {wheel_path.name}: "
                f"expected {wheel_record['sha256']}, got {actual_sha}"
            )
        metadata_name, metadata_version, tags = _wheel_metadata(wheel_path)
        if normalize_package_name(metadata_name) != normalize_package_name(dependency["name"]):
            raise LockError(
                f"wheel {wheel_path.name} metadata name {metadata_name} does not match "
                f"{dependency['name']}"
            )
        if metadata_version != dependency["version"]:
            raise LockError(
                f"wheel {wheel_path.name} metadata version {metadata_version} does not "
                f"match {dependency['version']}"
            )
        if not _wheel_supports_architecture(wheel_path, tags, platforms):
            raise LockError(f"wheel {wheel_path.name} is not compatible with {architecture}")
        verified.append(wheel_path.resolve())
    return verified


def _elf_machine(path: Path) -> int:
    try:
        header = path.read_bytes()[:20]
    except OSError as error:
        raise LockError(f"cannot read native library {path}: {error}") from error
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise LockError(f"native library is not ELF: {path}")
    byte_order = header[5]
    if byte_order not in (1, 2):
        raise LockError(f"native ELF has invalid byte order: {path}")
    return int.from_bytes(header[18:20], "little" if byte_order == 1 else "big")


def verify_install(lock_path: Path, architecture: str, site_packages: Path) -> list[str]:
    lock = load_lock(lock_path)
    if architecture not in ARCHITECTURES:
        raise LockError(f"unsupported architecture: {architecture}")
    if not site_packages.is_dir():
        raise LockError(f"site-packages does not exist: {site_packages}")

    installed: dict[str, tuple[str, Path]] = {}
    for metadata_path in sorted(site_packages.glob("*.dist-info/METADATA")):
        try:
            metadata = BytesParser().parsebytes(metadata_path.read_bytes())
        except OSError as error:
            raise LockError(f"cannot read installed metadata {metadata_path}: {error}") from error
        name = metadata.get("Name")
        version = metadata.get("Version")
        if not name or not version:
            raise LockError(f"installed metadata is incomplete: {metadata_path}")
        normalized_name = normalize_package_name(name)
        if normalized_name in installed:
            raise LockError(f"duplicate installed dependency: {name}")
        installed[normalized_name] = (version, metadata_path)

    expected = {
        normalize_package_name(dependency["name"]): dependency
        for dependency in lock["dependencies"]
    }
    missing = sorted(set(expected) - set(installed))
    extra = sorted(set(installed) - set(expected))
    if missing:
        raise LockError(f"missing installed dependencies: {', '.join(missing)}")
    if extra:
        raise LockError(f"extra installed dependencies: {', '.join(extra)}")
    for normalized_name, dependency in expected.items():
        installed_version = installed[normalized_name][0]
        if installed_version != dependency["version"]:
            raise LockError(
                f"installed {dependency['name']} version {installed_version} does not "
                f"match {dependency['version']}"
            )

    expected_machine = ARCHITECTURES[architecture]["elf_machine"]
    for native_library in sorted(site_packages.rglob("*.so")):
        actual_machine = _elf_machine(native_library)
        if actual_machine != expected_machine:
            raise LockError(
                f"{native_library} has native ELF machine {actual_machine}; "
                f"expected {expected_machine}"
            )
    return [f"{dependency['name']}=={dependency['version']}" for dependency in lock["dependencies"]]


def install_staged(
    lock_path: Path,
    architecture: str,
    staged_site_packages: Path,
    final_site_packages: Path,
) -> list[str]:
    """Replace base runtime packages with a verified lock-only installation."""
    verified = verify_install(lock_path, architecture, staged_site_packages)
    if final_site_packages.name != "site-packages":
        raise LockError(f"final install path must end in site-packages: {final_site_packages}")
    if final_site_packages.is_symlink():
        raise LockError(f"final site-packages must not be a symbolic link: {final_site_packages}")
    if final_site_packages.exists():
        shutil.rmtree(final_site_packages)
    final_site_packages.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(staged_site_packages, final_site_packages)
    final_verified = verify_install(lock_path, architecture, final_site_packages)
    if final_verified != verified:
        raise LockError("final site-packages differs from the verified staged installation")
    return final_verified


def main(arguments: list[str]) -> int:
    if len(arguments) < 2:
        raise LockError(
            "usage: runtime_lock.py requirements LOCK | "
            "verify-wheelhouse LOCK ARCH DIRECTORY | verify-install LOCK ARCH DIRECTORY | "
            "install-staged LOCK ARCH STAGED_DIRECTORY FINAL_DIRECTORY"
        )
    command = arguments[0]
    lock_path = Path(arguments[1])
    if command == "requirements" and len(arguments) == 2:
        lock = load_lock(lock_path)
        output = [
            f"{dependency['name']}=={dependency['version']}" for dependency in lock["dependencies"]
        ]
    elif command == "verify-wheelhouse" and len(arguments) == 4:
        output = [
            str(path) for path in verify_wheelhouse(lock_path, arguments[2], Path(arguments[3]))
        ]
    elif command == "verify-install" and len(arguments) == 4:
        output = verify_install(lock_path, arguments[2], Path(arguments[3]))
    elif command == "install-staged" and len(arguments) == 5:
        output = install_staged(
            lock_path,
            arguments[2],
            Path(arguments[3]),
            Path(arguments[4]),
        )
    else:
        raise LockError(f"invalid arguments for {command}")
    print("\n".join(output))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except LockError as error:
        print(f"runtime lock error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
