#!/usr/bin/env python3
"""Build a native, deterministic MCAV VJ runtime release artifact."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import sys
import tempfile
import urllib.parse
from pathlib import Path, PurePosixPath
from typing import Any

import build_managed_runtime

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIRECTORY.parent.parent
LOCK_PATH = SCRIPT_DIRECTORY / "runtime-lock.json"
SUPPORTED_TARGETS = ("linux-x86_64", "linux-aarch64", "windows-x86_64")
TOP_LEVEL_FIELDS = {
    "schema_version",
    "python",
    "release",
    "source_date_epoch",
    "runtimes",
    "dependencies",
}
RUNTIME_FIELDS = {"url", "sha256", "archive_size", "entrypoint", "pip_platforms"}
DEPENDENCY_FIELDS = {"name", "version", "license_expression", "wheels"}
WHEEL_FIELDS = {"filename", "url", "sha256", "size"}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
PYTHON_VERSION_PATTERN = re.compile(r"^3\.12\.[0-9]+$")
RELEASE_PATTERN = re.compile(r"^[0-9]{8}$")
PRODUCT_RELEASE_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-rc\.([1-9][0-9]*))?$"
)
EXPECTED_ENTRYPOINTS = {
    "linux-x86_64": "python/bin/python3.12",
    "linux-aarch64": "python/bin/python3.12",
    "windows-x86_64": "python/python.exe",
}
EXPECTED_PLATFORM_MARKERS = {
    "linux-x86_64": "x86_64",
    "linux-aarch64": "aarch64",
    "windows-x86_64": "win_amd64",
}
EXPECTED_RUNTIME_ARTIFACTS = {
    "linux-x86_64": "x86_64-unknown-linux-gnu-install_only_stripped.tar.gz",
    "linux-aarch64": "aarch64-unknown-linux-gnu-install_only_stripped.tar.gz",
    "windows-x86_64": "x86_64-pc-windows-msvc-install_only_stripped.tar.gz",
}


class ReleaseBuildError(ValueError):
    """Raised when a native release build cannot be trusted."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ReleaseBuildError(f"duplicate JSON field: {key}")
        value[key] = item
    return value


def _exact_fields(value: object, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReleaseBuildError(f"{label} must be an object")
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unknown = sorted(actual - expected)
        details = []
        if missing:
            details.append("missing fields: " + ", ".join(missing))
        if unknown:
            details.append("unknown fields: " + ", ".join(unknown))
        raise ReleaseBuildError(f"{label} has " + "; ".join(details))
    return value


def _required_string(value: object, label: str, maximum: int = 256) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ReleaseBuildError(f"{label} must be a non-empty bounded string")
    return value


def _validate_digest(value: object, label: str) -> str:
    digest = _required_string(value, label, 64)
    if SHA256_PATTERN.fullmatch(digest) is None:
        raise ReleaseBuildError(f"{label} must be a lowercase SHA-256 digest")
    return digest


def _validate_runtime(
    target: str, value: object, release: str, python_version: str
) -> dict[str, Any]:
    runtime = _exact_fields(value, RUNTIME_FIELDS, f"runtime {target}")
    url = _required_string(runtime["url"], f"runtime {target} url", 1000)
    parsed = urllib.parse.urlsplit(url)
    expected_prefix = f"/astral-sh/python-build-standalone/releases/download/{release}/"
    if (
        parsed.scheme != "https"
        or parsed.hostname != "github.com"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port is not None
        or parsed.query
        or parsed.fragment
        or not parsed.path.startswith(expected_prefix)
    ):
        raise ReleaseBuildError(f"runtime {target} url must be an official pinned HTTPS release")
    expected_filename = f"cpython-{python_version}%2B{release}-{EXPECTED_RUNTIME_ARTIFACTS[target]}"
    if not parsed.path.endswith("/" + expected_filename):
        raise ReleaseBuildError(f"runtime {target} url does not select its target artifact")
    _validate_digest(runtime["sha256"], f"runtime {target} sha256")
    archive_size = runtime["archive_size"]
    if isinstance(archive_size, bool) or not isinstance(archive_size, int):
        raise ReleaseBuildError(f"runtime {target} archive_size must be an integer")
    if not 20_000_000 <= archive_size <= 200_000_000:
        raise ReleaseBuildError(f"runtime {target} archive_size is outside the allowed range")
    if runtime["entrypoint"] != EXPECTED_ENTRYPOINTS[target]:
        raise ReleaseBuildError(f"runtime {target} entrypoint is not canonical")
    pip_platforms = runtime["pip_platforms"]
    if (
        not isinstance(pip_platforms, list)
        or not pip_platforms
        or len(pip_platforms) > 8
        or any(not isinstance(item, str) or not item or len(item) > 100 for item in pip_platforms)
        or len(pip_platforms) != len(set(pip_platforms))
    ):
        raise ReleaseBuildError(f"runtime {target} pip_platforms must be unique bounded strings")
    marker = EXPECTED_PLATFORM_MARKERS[target]
    if any(marker not in item for item in pip_platforms):
        raise ReleaseBuildError(f"runtime {target} pip_platforms contain a platform tag mismatch")
    return runtime


def _validate_wheel(target: str, value: object, dependency: str) -> None:
    wheel = _exact_fields(value, WHEEL_FIELDS, f"dependency {dependency} wheel {target}")
    filename = _required_string(
        wheel["filename"], f"dependency {dependency} wheel {target} filename", 300
    )
    path = PurePosixPath(filename)
    if path.name != filename or not filename.endswith(".whl"):
        raise ReleaseBuildError(f"dependency {dependency} wheel filename is not canonical")
    url = _required_string(wheel["url"], f"dependency {dependency} wheel {target} url", 1000)
    parsed_url = urllib.parse.urlsplit(url)
    if (
        parsed_url.scheme != "https"
        or parsed_url.hostname != "files.pythonhosted.org"
        or parsed_url.username is not None
        or parsed_url.password is not None
        or parsed_url.port is not None
        or parsed_url.query
        or parsed_url.fragment
        or not parsed_url.path.endswith("/" + filename)
    ):
        raise ReleaseBuildError(
            f"dependency {dependency} wheel {target} url must pin its PyPI artifact"
        )
    size = wheel["size"]
    if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= 100_000_000:
        raise ReleaseBuildError(f"dependency {dependency} wheel {target} size is invalid")
    wheel_parts = filename[:-4].split("-")
    normalized_dependency = re.sub(r"[-_.]+", "-", dependency).casefold()
    if (
        len(wheel_parts) < 5
        or re.sub(r"[-_.]+", "-", wheel_parts[0]).casefold() != normalized_dependency
    ):
        raise ReleaseBuildError(
            f"dependency {dependency} wheel distribution or version does not match the lock"
        )
    if target == "any":
        if not filename.endswith("-none-any.whl"):
            raise ReleaseBuildError(
                f"dependency {dependency} any wheel has a platform tag mismatch"
            )
    elif EXPECTED_PLATFORM_MARKERS[target] not in filename:
        raise ReleaseBuildError(
            f"dependency {dependency} wheel {target} has a platform tag mismatch"
        )
    _validate_digest(wheel["sha256"], f"dependency {dependency} wheel {target} sha256")


def load_runtime_lock(path: Path = LOCK_PATH) -> dict[str, Any]:
    """Load and fail-closed validate the complete runtime dependency lock."""
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseBuildError(f"unable to read runtime lock: {error}") from error
    lock = _exact_fields(value, TOP_LEVEL_FIELDS, "runtime lock")
    if isinstance(lock["schema_version"], bool) or lock["schema_version"] != 1:
        raise ReleaseBuildError("runtime lock schema_version must be 1")
    python_version = _required_string(lock["python"], "runtime lock python", 32)
    if PYTHON_VERSION_PATTERN.fullmatch(python_version) is None:
        raise ReleaseBuildError("runtime lock python must pin Python 3.12.x")
    release = _required_string(lock["release"], "runtime lock release", 8)
    if RELEASE_PATTERN.fullmatch(release) is None:
        raise ReleaseBuildError("runtime lock release must be YYYYMMDD")
    source_date_epoch = lock["source_date_epoch"]
    if (
        isinstance(source_date_epoch, bool)
        or not isinstance(source_date_epoch, int)
        or source_date_epoch != 315532800
    ):
        raise ReleaseBuildError("runtime lock source_date_epoch must be the ZIP epoch")
    runtimes = lock["runtimes"]
    if not isinstance(runtimes, dict) or set(runtimes) != set(SUPPORTED_TARGETS):
        raise ReleaseBuildError("runtime lock must contain exactly all supported native targets")
    for target in SUPPORTED_TARGETS:
        _validate_runtime(target, runtimes[target], release, python_version)
    dependencies = lock["dependencies"]
    if not isinstance(dependencies, list) or not dependencies or len(dependencies) > 200:
        raise ReleaseBuildError("runtime lock dependencies must be a non-empty bounded list")
    names: list[str] = []
    filenames: list[str] = []
    for index, item in enumerate(dependencies):
        dependency = _exact_fields(item, DEPENDENCY_FIELDS, f"dependency {index}")
        name = _required_string(dependency["name"], f"dependency {index} name", 100)
        version = _required_string(dependency["version"], f"dependency {name} version", 100)
        license_expression = _required_string(
            dependency["license_expression"], f"dependency {name} license_expression", 200
        )
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
            raise ReleaseBuildError(f"dependency {name} has an invalid name")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.!+_-]*", version):
            raise ReleaseBuildError(f"dependency {name} has an invalid version")
        if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9().+ -]*", license_expression) is None:
            raise ReleaseBuildError(f"dependency {name} has an invalid license_expression")
        wheels = dependency["wheels"]
        if not isinstance(wheels, dict):
            raise ReleaseBuildError(f"dependency {name} wheels must be an object")
        keys = set(wheels)
        if keys == {"any"}:
            selected_targets = ("any",)
        elif keys == set(SUPPORTED_TARGETS):
            selected_targets = SUPPORTED_TARGETS
        else:
            missing = next((target for target in SUPPORTED_TARGETS if target not in keys), "any")
            raise ReleaseBuildError(f"dependency {name} must pin wheel for {missing}")
        for target in selected_targets:
            _validate_wheel(target, wheels[target], name)
            wheel_version = wheels[target]["filename"][:-4].split("-")[1]
            if wheel_version.replace("_", "-") != version.replace("_", "-"):
                raise ReleaseBuildError(
                    f"dependency {name} wheel distribution or version does not match the lock"
                )
            filenames.append(wheels[target]["filename"].casefold())
        names.append(re.sub(r"[-_.]+", "-", name).casefold())
    if len(names) != len(set(names)):
        raise ReleaseBuildError("runtime lock contains duplicate dependencies")
    if len(filenames) != len(set(filenames)):
        raise ReleaseBuildError("runtime lock contains duplicate wheel filenames")
    wheel_bytes = sum(
        wheel["size"] for dependency in dependencies for wheel in dependency["wheels"].values()
    )
    if wheel_bytes > 500_000_000:
        raise ReleaseBuildError("runtime lock wheel cache exceeds the aggregate size limit")
    return lock


def detect_native_target(system: str | None = None, machine: str | None = None) -> str:
    """Return the supported release target for the current native host."""
    operating_system = (system or platform.system()).casefold()
    architecture = (machine or platform.machine()).casefold()
    aliases = {
        ("linux", "x86_64"): "linux-x86_64",
        ("linux", "amd64"): "linux-x86_64",
        ("linux", "aarch64"): "linux-aarch64",
        ("linux", "arm64"): "linux-aarch64",
        ("windows", "amd64"): "windows-x86_64",
        ("windows", "x86_64"): "windows-x86_64",
    }
    try:
        return aliases[(operating_system, architecture)]
    except KeyError as error:
        raise ReleaseBuildError(
            f"unsupported native release host: {system or platform.system()} "
            f"{machine or platform.machine()}"
        ) from error


def require_native_target(target: str, detected: str | None = None) -> None:
    if target not in SUPPORTED_TARGETS:
        raise ReleaseBuildError(f"unsupported release target: {target}")
    native = detected or detect_native_target()
    if target != native:
        raise ReleaseBuildError(f"release target {target} is not native target {native}")


def release_archive_name(version: str, target: str) -> str:
    if PRODUCT_RELEASE_PATTERN.fullmatch(version) is None:
        raise ReleaseBuildError("release version must be stable semver or stable semver-rc.N")
    if target not in SUPPORTED_TARGETS:
        raise ReleaseBuildError(f"unsupported release target: {target}")
    return f"mcav-vj-runtime-{version}-{target}.zip"


def verify_product_version(version: str) -> None:
    release_scripts = REPOSITORY_ROOT / "scripts" / "release"
    if str(release_scripts) not in sys.path:
        sys.path.insert(0, str(release_scripts))
    from verify_versions import ContractError, verify_repository

    try:
        verify_repository(REPOSITORY_ROOT, version)
    except ContractError as error:
        raise ReleaseBuildError(f"release version contract failed: {error}") from error


def build_release(target: str, version: str, output_directory: Path) -> Path:
    """Build and independently verify one native release archive."""
    require_native_target(target)
    release_archive_name(version, target)
    verify_product_version(version)
    lock = load_runtime_lock()
    running_python = ".".join(str(item) for item in sys.version_info[:3])
    if running_python != lock["python"]:
        raise ReleaseBuildError(
            f"release build requires locked Python {lock['python']}, got {running_python}"
        )
    requested_epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if requested_epoch is not None and requested_epoch != str(lock["source_date_epoch"]):
        raise ReleaseBuildError("SOURCE_DATE_EPOCH does not match the runtime lock")
    os.environ["SOURCE_DATE_EPOCH"] = str(lock["source_date_epoch"])
    archive_name = release_archive_name(version, target)
    output_directory = output_directory.resolve()
    output_directory.mkdir(parents=True, exist_ok=True)
    destination = output_directory / archive_name
    with tempfile.TemporaryDirectory(prefix="mcav-native-release-") as temporary_value:
        development_archive, _ = build_managed_runtime.build(target, Path(temporary_value))
        with tempfile.TemporaryDirectory(
            prefix=".mcav-release-stage-", dir=output_directory
        ) as staging_value:
            staged = Path(staging_value) / archive_name
            shutil.copyfile(development_archive, staged)
            from verify_release_runtime import verify_release_archive

            verify_release_archive(
                staged,
                target,
                lock_path=LOCK_PATH,
                smoke=True,
                trusted_local_build=True,
                version=version,
            )
            os.replace(staged, destination)
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=SUPPORTED_TARGETS)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        archive = build_release(arguments.target, arguments.version, arguments.output)
    except (OSError, ReleaseBuildError, ValueError) as error:
        parser.exit(1, f"release runtime build failed: {error}\n")
    print(archive)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
