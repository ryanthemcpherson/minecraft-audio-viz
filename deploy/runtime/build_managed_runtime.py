#!/usr/bin/env python3
"""Build a deterministic, Java-verifiable MCAV managed runtime archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform as host_platform
import shutil
import stat
import subprocess  # nosec B404 -- this release builder invokes fixed local tools without a shell.
import sys
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
import zlib
from pathlib import Path, PurePosixPath
from typing import Any

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIRECTORY.parent.parent
LOCK_PATH = SCRIPT_DIRECTORY / "runtime-lock.json"
CACHE_DIRECTORY = SCRIPT_DIRECTORY / ".cache"
ARCHIVE_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
SUPPORTED_PLATFORMS = ("linux-x86_64", "linux-aarch64", "windows-x86_64")
PACKAGE_ROOTS = (
    "LICENSE",
    "admin_panel",
    "configs/banners",
    "configs/dj_auth.example.json",
    "configs/scenes",
    "patterns",
    "preview_tool/frontend",
    "vj_server",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")


def load_lock() -> dict[str, Any]:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock.get("schema_version") != 1 or tuple(sorted(lock.get("runtimes", {}))) != tuple(
        sorted(SUPPORTED_PLATFORMS)
    ):
        raise ValueError("runtime lock has an unsupported schema or platform set")
    return lock


def download_verified(
    url: str,
    expected_sha256: str,
    destination: Path,
    expected_size: int | None = None,
) -> None:
    parsed_url = urllib.parse.urlsplit(url)
    if (
        parsed_url.scheme != "https"
        or parsed_url.hostname != "github.com"
        or parsed_url.username is not None
        or parsed_url.password is not None
        or parsed_url.query
        or parsed_url.fragment
        or not parsed_url.path.startswith("/astral-sh/python-build-standalone/releases/download/")
    ):
        raise ValueError("portable Python URL must be an official pinned HTTPS release")
    download_verified_payload(
        url,
        expected_sha256,
        destination,
        expected_size,
        "portable Python",
    )


def download_verified_payload(
    url: str,
    expected_sha256: str,
    destination: Path,
    expected_size: int | None,
    label: str,
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if (
        destination.is_file()
        and not destination.is_symlink()
        and (expected_size is None or destination.stat().st_size == expected_size)
        and sha256_file(destination) == expected_sha256
    ):
        return
    temporary = destination.with_name(f".{destination.name}.download")
    temporary.unlink(missing_ok=True)
    print(f"Downloading {destination.name}...", flush=True)
    try:
        last_error: BaseException | None = None
        for attempt in range(1, 4):
            try:
                with (
                    urllib.request.urlopen(  # nosec B310 -- callers restrict URL origins.
                        url, timeout=60
                    ) as response,
                    temporary.open("wb") as target,
                ):
                    content_length = response.headers.get("Content-Length")
                    if (
                        expected_size is not None
                        and content_length is not None
                        and int(content_length) != expected_size
                    ):
                        raise ValueError(
                            f"size mismatch for {label} {destination.name}: "
                            f"expected {expected_size}, got {content_length}"
                        )
                    maximum_size = expected_size if expected_size is not None else 200_000_000
                    written = 0
                    while chunk := response.read(1024 * 1024):
                        written += len(chunk)
                        if written > maximum_size:
                            raise ValueError(
                                f"download exceeds size bound for {label} {destination.name}"
                            )
                        target.write(chunk)
                break
            except (OSError, TimeoutError) as error:
                last_error = error
                temporary.unlink(missing_ok=True)
                if attempt == 3:
                    raise
                time.sleep(attempt)
        if last_error is not None and not temporary.is_file():
            raise last_error
        actual_sha256 = sha256_file(temporary)
        if expected_size is not None and temporary.stat().st_size != expected_size:
            raise ValueError(
                f"size mismatch for {destination.name}: expected {expected_size}, "
                f"got {temporary.stat().st_size}"
            )
        if actual_sha256 != expected_sha256:
            raise ValueError(
                f"SHA-256 mismatch for {label} {destination.name}: expected {expected_sha256}, "
                f"got {actual_sha256}"
            )
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def extract_python(archive: Path, destination: Path) -> None:
    with tarfile.open(archive, "r:gz") as source:
        source.extractall(destination, filter="data")
    python_root = destination / "python"
    if not python_root.is_dir():
        raise ValueError("portable Python archive did not contain python/")


def normalize_python_license(runtime_root: Path, python_version: str) -> None:
    destination = runtime_root / "python/LICENSE.txt"
    if destination.is_file() and destination.stat().st_size > 0:
        return
    major_minor = python_version.rsplit(".", 1)[0]
    source = runtime_root / f"python/lib/python{major_minor}/LICENSE.txt"
    if not source.is_file() or source.stat().st_size == 0:
        raise ValueError("portable Python runtime does not contain its license")
    shutil.copyfile(source, destination)


def locked_wheels(lock: dict[str, Any], platform: str) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    for dependency in lock["dependencies"]:
        wheels = dependency["wheels"]
        wheel = wheels.get(platform, wheels.get("any"))
        if wheel is None:
            raise ValueError(f"dependency {dependency['name']} has no wheel for {platform}")
        selected.append(
            {
                "license_expression": dependency["license_expression"],
                "name": dependency["name"],
                "version": dependency["version"],
                **wheel,
            }
        )
    filenames = [item["filename"].casefold() for item in selected]
    if len(filenames) != len(set(filenames)):
        raise ValueError("runtime lock selects duplicate wheel filenames")
    if sum(item["size"] for item in selected) > 250_000_000:
        raise ValueError("selected runtime wheels exceed the bounded wheelhouse size")
    return selected


def download_locked_wheel(wheel: dict[str, Any], destination: Path) -> None:
    url = wheel["url"]
    filename = wheel["filename"]
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
        raise ValueError(f"locked wheel URL is not a pinned PyPI file: {filename}")
    download_verified_payload(
        url,
        wheel["sha256"],
        destination,
        wheel["size"],
        "wheel",
    )


def prune_wheel_cache(wheel_cache: Path, expected_wheels: dict[str, tuple[int, str]]) -> None:
    wheel_cache.mkdir(parents=True, exist_ok=True)
    resolved_cache = wheel_cache.resolve()
    for path in wheel_cache.iterdir():
        expected = expected_wheels.get(path.name)
        if (
            expected is not None
            and path.is_file()
            and not path.is_symlink()
            and path.stat().st_size == expected[0]
            and sha256_file(path) == expected[1]
        ):
            continue
        if path.parent.resolve() != resolved_cache or (
            not path.is_file() and not path.is_symlink()
        ):
            raise ValueError(f"unexpected entry in managed wheel cache: {path.name}")
        path.unlink()


def write_third_party_license_manifest(lock: dict[str, Any], runtime_root: Path) -> None:
    dependencies = [
        {
            "license_expression": dependency["license_expression"],
            "name": dependency["name"],
            "version": dependency["version"],
        }
        for dependency in sorted(lock["dependencies"], key=lambda item: item["name"].casefold())
    ]
    (runtime_root / "THIRD_PARTY_LICENSES.json").write_bytes(
        canonical_json({"dependencies": dependencies, "schema_version": 1})
    )


def install_dependencies(
    lock: dict[str, Any], platform: str, runtime_root: Path, temporary_root: Path
) -> None:
    selected = locked_wheels(lock, platform)
    requirements = temporary_root / "requirements.txt"
    requirements.write_text(
        "".join(
            f"{item['name']}=={item['version']} --hash=sha256:{item['sha256']}\n"
            for item in selected
        ),
        encoding="utf-8",
        newline="\n",
    )
    wheelhouse = temporary_root / "wheelhouse"
    wheelhouse.mkdir()
    all_locked_wheels = {
        wheel["filename"]: (wheel["size"], wheel["sha256"])
        for dependency in lock["dependencies"]
        for wheel in dependency["wheels"].values()
    }
    wheel_cache = CACHE_DIRECTORY / "wheels"
    prune_wheel_cache(wheel_cache, all_locked_wheels)
    for item in selected:
        cached_wheel = wheel_cache / item["filename"]
        download_locked_wheel(item, cached_wheel)
        shutil.copyfile(cached_wheel, wheelhouse / item["filename"])
    platform_args = [
        argument
        for tag in lock["runtimes"][platform]["pip_platforms"]
        for argument in ("--platform", tag)
    ]
    common = [
        sys.executable,
        "-m",
        "pip",
        "--disable-pip-version-check",
        "--quiet",
        "--no-cache-dir",
    ]
    expected = {item["filename"]: item["sha256"] for item in selected}
    observed = {path.name: sha256_file(path) for path in wheelhouse.iterdir() if path.is_file()}
    if observed != expected:
        raise ValueError(
            f"wheelhouse differs from runtime lock: expected={expected}, observed={observed}"
        )

    site_packages = (
        runtime_root / "python/Lib/site-packages"
        if platform == "windows-x86_64"
        else runtime_root / "python/lib/python3.12/site-packages"
    )
    site_packages.mkdir(parents=True, exist_ok=True)
    subprocess.run(  # nosec B603 -- argv is built from fixed pip options and the lock.
        common
        + [
            "install",
            "--target",
            str(site_packages),
            "--require-hashes",
            "--no-deps",
            "--no-index",
            "--find-links",
            str(wheelhouse),
            "--no-compile",
            "--only-binary=:all:",
            "--implementation",
            "cp",
            "--python-version",
            "3.12",
        ]
        + platform_args
        + ["--requirement", str(requirements)],
        check=True,
    )


def copy_tracked_regular_file(source: Path, destination: Path) -> None:
    if source.is_symlink():
        raise ValueError(f"tracked runtime source must not be a symlink: {source}")
    if not source.is_file():
        raise ValueError(f"tracked runtime source must be a regular file: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)


def copy_product_files(runtime_root: Path) -> None:
    command = ["git", "-C", str(REPOSITORY_ROOT), "ls-files", "-z", "--", *PACKAGE_ROOTS]
    result = subprocess.run(  # nosec B603 -- fixed git executable and repository paths.
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0 and shutil.which("git.exe") and shutil.which("wslpath"):
        windows_root = subprocess.run(  # nosec B603 -- fixed WSL path translator.
            ["wslpath", "-w", str(REPOSITORY_ROOT)],
            check=True,
            stdout=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        result = subprocess.run(  # nosec B603 -- fixed git executable and repository paths.
            ["git.exe", "-C", windows_root, "ls-files", "-z", "--", *PACKAGE_ROOTS],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    if result.returncode != 0:
        raise subprocess.CalledProcessError(
            result.returncode, command, result.stdout, result.stderr
        )
    for raw_path in result.stdout.split(b"\0"):
        if not raw_path:
            continue
        relative = Path(os.fsdecode(raw_path))
        portable_parts = PurePosixPath(relative.as_posix()).parts
        if "tests" in portable_parts or relative.suffix in {".pyc", ".pyo"}:
            continue
        if relative.parts[0] == "vj_server" and relative.suffix not in {".py", ".md", ".toml"}:
            continue
        source = REPOSITORY_ROOT / relative
        destination = runtime_root / relative
        copy_tracked_regular_file(source, destination)


def remove_owned_path(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def trim_runtime(runtime_root: Path, platform: str, entrypoint: str) -> None:
    for relative in (
        "python/include",
        "python/share",
        "python/lib/pkgconfig",
        "python/tcl",
    ):
        remove_owned_path(runtime_root / relative)
    for path in sorted(runtime_root.rglob("*"), reverse=True):
        if path.is_symlink():
            path.unlink()
        elif path.is_dir() and path.name == "bin" and path.parent.name == "site-packages":
            # pip --target creates optional console-script shims with a shebang that
            # embeds the host build interpreter. The managed runtime invokes modules
            # directly and does not expose these development-only entry points.
            shutil.rmtree(path)
        elif path.is_dir() and path.name in {"__pycache__", "test", "tests"}:
            shutil.rmtree(path)
        elif path.is_file() and path.suffix in {".pyc", ".pyo"}:
            path.unlink()
    entrypoint_path = runtime_root / entrypoint
    if not entrypoint_path.is_file() or entrypoint_path.is_symlink():
        raise ValueError(f"runtime entrypoint is missing after trimming: {entrypoint}")
    if platform.startswith("linux-"):
        bin_directory = runtime_root / "python/bin"
        for candidate in bin_directory.iterdir():
            if candidate != entrypoint_path:
                remove_owned_path(candidate)


def detect_native_build_target(system: str | None = None, machine: str | None = None) -> str:
    operating_system = (system or host_platform.system()).casefold()
    architecture = (machine or host_platform.machine()).casefold()
    targets = {
        ("linux", "x86_64"): "linux-x86_64",
        ("linux", "amd64"): "linux-x86_64",
        ("linux", "aarch64"): "linux-aarch64",
        ("linux", "arm64"): "linux-aarch64",
        ("windows", "amd64"): "windows-x86_64",
        ("windows", "x86_64"): "windows-x86_64",
    }
    try:
        return targets[(operating_system, architecture)]
    except KeyError as error:
        raise ValueError(
            f"unsupported managed-runtime build host: "
            f"{system or host_platform.system()} {machine or host_platform.machine()}"
        ) from error


def locked_build_python(
    lock: dict[str, Any],
    platform: str,
    runtime_root: Path,
    temporary_root: Path,
) -> Path:
    native_target = detect_native_build_target()
    if platform == native_target:
        interpreter = runtime_root / lock["runtimes"][platform]["entrypoint"]
    elif native_target == "linux-x86_64":
        build_runtime = lock["runtimes"]["linux-x86_64"]
        build_archive = CACHE_DIRECTORY / f"python-{lock['python']}-linux-x86_64.tar.gz"
        download_verified(
            build_runtime["url"],
            build_runtime["sha256"],
            build_archive,
            build_runtime.get("archive_size"),
        )
        build_root = temporary_root / "locked-build-python"
        build_root.mkdir()
        extract_python(build_archive, build_root)
        interpreter = build_root / build_runtime["entrypoint"]
    else:
        raise ValueError(
            f"cross-build bytecode compilation requires linux-x86_64, got {native_target}"
        )
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    observed_version = subprocess.run(  # nosec B603 -- SHA-locked interpreter path.
        [str(interpreter), "-c", "import platform; print(platform.python_version())"],
        check=True,
        env=environment,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if observed_version != lock["python"]:
        raise ValueError(
            f"locked build interpreter mismatch: expected {lock['python']}, got {observed_version}"
        )
    return interpreter


def compile_bytecode(runtime_root: Path, interpreter: Path) -> None:
    environment = dict(os.environ)
    environment["PYTHONHASHSEED"] = "0"
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    subprocess.run(  # nosec B603 -- SHA-locked interpreter and fixed compileall argv.
        [
            str(interpreter),
            "-m",
            "compileall",
            "-q",
            "--invalidation-mode",
            "checked-hash",
            "-s",
            str(runtime_root),
            "-p",
            "",
            str(runtime_root),
        ],
        check=True,
        env=environment,
    )


def validate_no_local_build_paths(runtime_root: Path, build_root: Path) -> None:
    local_paths = {
        str(REPOSITORY_ROOT.resolve()),
        str(build_root.resolve()),
        str(Path(sys.executable).resolve()),
    }
    forbidden = {
        value.encode("utf-8")
        for path in local_paths
        for value in (path, path.replace("\\", "/"), path.replace("/", "\\"))
        if len(value) >= 8
    }
    for path in sorted(item for item in runtime_root.rglob("*") if item.is_file()):
        payload = path.read_bytes()
        if any(value in payload for value in forbidden):
            relative = path.relative_to(runtime_root).as_posix()
            raise ValueError(f"managed runtime payload leaks a local build path: {relative}")


def runtime_files(runtime_root: Path) -> list[Path]:
    return sorted(
        (item for item in runtime_root.rglob("*") if item.is_file()),
        key=lambda item: item.relative_to(runtime_root).as_posix(),
    )


def build_inventory(runtime_root: Path, entrypoint: str) -> bytes:
    files = []
    for path in runtime_files(runtime_root):
        relative = path.relative_to(runtime_root).as_posix()
        if relative == "files.json":
            raise ValueError("runtime staging unexpectedly contains files.json")
        files.append(
            {
                "executable": relative == entrypoint,
                "path": relative,
                "sha256": sha256_file(path),
                "size": path.stat().st_size,
            }
        )
    if sum(1 for item in files if item["executable"]) != 1:
        raise ValueError("runtime inventory must contain exactly one executable")
    return canonical_json({"files": files, "schema_version": 1})


def zip_info(name: str, executable: bool, payload: bytes) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ARCHIVE_TIMESTAMP)
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | (0o700 if executable else 0o600)) << 16
    info.compress_type = (
        zipfile.ZIP_STORED
        if name == "files.json" or len(payload) > max(1, len(zlib.compress(payload, 9))) * 90
        else zipfile.ZIP_DEFLATED
    )
    return info


def create_archive(runtime_root: Path, archive_path: Path, entrypoint: str) -> dict[str, Any]:
    files_manifest = build_inventory(runtime_root, entrypoint)
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_archive = archive_path.with_name(f".{archive_path.name}.tmp")
    temporary_archive.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(temporary_archive, "w", compresslevel=9, allowZip64=True) as archive:
            for path in runtime_files(runtime_root):
                relative = path.relative_to(runtime_root).as_posix()
                payload = path.read_bytes()
                archive.writestr(zip_info(relative, relative == entrypoint, payload), payload)
            archive.writestr(zip_info("files.json", False, files_manifest), files_manifest)
        os.replace(temporary_archive, archive_path)
    finally:
        temporary_archive.unlink(missing_ok=True)

    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        uncompressed_size = sum(entry.file_size for entry in entries)
        if len(entries) != len({entry.filename.casefold() for entry in entries}):
            raise ValueError("managed runtime archive contains duplicate or case-colliding entries")
        for entry in entries:
            if entry.file_size > max(1, entry.compress_size) * 100:
                raise ValueError(f"managed runtime entry exceeds verifier ratio: {entry.filename}")
    return {
        "archive_size": archive_path.stat().st_size,
        "entrypoint": entrypoint,
        "files_manifest_sha256": hashlib.sha256(files_manifest).hexdigest(),
        "sha256": sha256_file(archive_path),
        "uncompressed_size": uncompressed_size,
    }


def create_archive_locked(
    interpreter: Path,
    runtime_root: Path,
    archive_path: Path,
    entrypoint: str,
) -> dict[str, Any]:
    result = subprocess.run(  # nosec B603 -- SHA-locked interpreter and internal argv.
        [
            str(interpreter),
            str(Path(__file__).resolve()),
            "--package-staging",
            str(runtime_root),
            "--archive",
            str(archive_path),
            "--entrypoint",
            entrypoint,
        ],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return json.loads(result.stdout)


def archive_difference_summary(first: Path, second: Path) -> str:
    with zipfile.ZipFile(first) as first_archive, zipfile.ZipFile(second) as second_archive:
        first_files = {
            item["path"]: item for item in json.loads(first_archive.read("files.json"))["files"]
        }
        second_files = {
            item["path"]: item for item in json.loads(second_archive.read("files.json"))["files"]
        }
    changed = [
        path
        for path in sorted(first_files.keys() | second_files.keys())
        if first_files.get(path) != second_files.get(path)
    ]
    if changed:
        preview = ", ".join(changed[:20])
        suffix = "" if len(changed) <= 20 else f" (+{len(changed) - 20} more)"
        return f"inventory differs at: {preview}{suffix}"
    return "inventories match; ZIP encoding differs"


def prepare_runtime_staging(
    lock: dict[str, Any],
    platform: str,
    python_archive: Path,
    build_root: Path,
) -> tuple[Path, Path]:
    runtime_root = build_root / "runtime"
    runtime_root.mkdir(parents=True)
    extract_python(python_archive, runtime_root)
    normalize_python_license(runtime_root, lock["python"])
    install_dependencies(lock, platform, runtime_root, build_root)
    write_third_party_license_manifest(lock, runtime_root)
    copy_product_files(runtime_root)
    runtime = lock["runtimes"][platform]
    trim_runtime(runtime_root, platform, runtime["entrypoint"])
    build_python = locked_build_python(lock, platform, runtime_root, build_root)
    compile_bytecode(runtime_root, build_python)
    validate_no_local_build_paths(runtime_root, build_root)
    return runtime_root, build_python


def build(platform: str, output_directory: Path) -> tuple[Path, Path]:
    lock = load_lock()
    runtime = lock["runtimes"][platform]
    CACHE_DIRECTORY.mkdir(parents=True, exist_ok=True)
    python_archive = CACHE_DIRECTORY / f"python-{lock['python']}-{platform}.tar.gz"
    download_verified(
        runtime["url"],
        runtime["sha256"],
        python_archive,
        runtime.get("archive_size"),
    )
    output_directory.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="mcav-runtime-") as temporary_value:
        temporary_root = Path(temporary_value)
        runtime_root, build_python = prepare_runtime_staging(
            lock,
            platform,
            python_archive,
            temporary_root / "first-build",
        )
        repeated_runtime_root, repeated_build_python = prepare_runtime_staging(
            lock,
            platform,
            python_archive,
            temporary_root / "second-build",
        )
        archive_path = output_directory / f"mcav-runtime-{platform}.zip"
        with tempfile.NamedTemporaryFile(
            prefix=f".{archive_path.name}.",
            suffix=".build",
            dir=output_directory,
            delete=False,
        ) as temporary_archive_file:
            staged_archive = Path(temporary_archive_file.name)
        staged_archive.unlink()
        reproducibility_archive = temporary_root / "reproducibility.zip"
        try:
            artifact = create_archive_locked(
                build_python,
                runtime_root,
                staged_archive,
                runtime["entrypoint"],
            )
            repeated = create_archive_locked(
                repeated_build_python,
                repeated_runtime_root,
                reproducibility_archive,
                runtime["entrypoint"],
            )
            if artifact != repeated or sha256_file(staged_archive) != sha256_file(
                reproducibility_archive
            ):
                details = archive_difference_summary(staged_archive, reproducibility_archive)
                raise ValueError(
                    "managed runtime build is not byte-for-byte reproducible: " + details
                )
            os.replace(staged_archive, archive_path)
        finally:
            staged_archive.unlink(missing_ok=True)

    artifact.update(
        {
            "filename": archive_path.name,
            "platform": platform,
            "python": lock["python"],
            "schema_version": 1,
        }
    )
    metadata_path = output_directory / f"mcav-runtime-{platform}.artifact.json"
    temporary_metadata = metadata_path.with_name(f".{metadata_path.name}.tmp")
    temporary_metadata.write_bytes(canonical_json(artifact))
    os.replace(temporary_metadata, metadata_path)
    return archive_path, metadata_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=SUPPORTED_PLATFORMS)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--package-staging", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--archive", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--entrypoint", help=argparse.SUPPRESS)
    arguments = parser.parse_args()
    if arguments.package_staging is not None:
        if arguments.archive is None or arguments.entrypoint is None:
            parser.error("internal archive mode requires --archive and --entrypoint")
        print(
            json.dumps(
                create_archive(
                    arguments.package_staging,
                    arguments.archive,
                    arguments.entrypoint,
                ),
                separators=(",", ":"),
                sort_keys=True,
            )
        )
        return
    if arguments.platform is None or arguments.output is None:
        parser.error("--platform and --output are required")
    archive, metadata = build(arguments.platform, arguments.output.resolve())
    print(f"Managed runtime: {archive}")
    print(f"Artifact metadata: {metadata}")


if __name__ == "__main__":
    main()
