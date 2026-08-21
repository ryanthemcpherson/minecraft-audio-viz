"""Secure, idempotent bootstrap for the SFTP-only Pterodactyl bundle."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
import secrets
import shlex
import shutil
import ssl
import stat
import subprocess  # nosec B404 - fixed OpenSSL argument vectors; shell execution is never used
import tempfile
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable, Iterator, Mapping, Sequence

from vj_server.auth import hash_password


class BootstrapError(RuntimeError):
    """A deployment state problem that must not be repaired destructively."""


@dataclass(frozen=True)
class BootstrapPaths:
    project_root: Path
    plugins_dir: Path

    @property
    def release_jar(self) -> Path:
        return self.project_root / "release" / "AudioViz.jar"

    @property
    def default_config(self) -> Path:
        return self.project_root / "release" / "plugin-config.default.yml"

    @property
    def state_dir(self) -> Path:
        return self.project_root / "state"

    @property
    def backups_dir(self) -> Path:
        return self.project_root / "backups"

    @property
    def identity_generations(self) -> Path:
        return self.state_dir / "identity-generations"

    @property
    def identity_pointer(self) -> Path:
        return self.state_dir / "current-identity"

    @property
    def identity_lock(self) -> Path:
        return self.state_dir / ".bootstrap.lock"

    @property
    def runtime_env(self) -> Path:
        return self.state_dir / "runtime.env"

    @property
    def auth_file(self) -> Path:
        return self.state_dir / "dj_auth.json"

    @property
    def tls_cert(self) -> Path:
        return self.state_dir / "tls.crt"

    @property
    def tls_key(self) -> Path:
        return self.state_dir / "tls.key"

    @property
    def identity_metadata(self) -> Path:
        return self.state_dir / "identity.json"

    @property
    def first_login(self) -> Path:
        return self.project_root / "FIRST_LOGIN.txt"

    @property
    def plugin_config(self) -> Path:
        return self.plugins_dir / "AudioViz" / "config.yml"


@dataclass(frozen=True)
class BootstrapResult:
    credentials_created: bool
    plugin_installed: bool
    config_updated: bool
    first_login: Path
    auth_file: Path
    tls_cert: Path


CommandRunner = Callable[[Sequence[str]], subprocess.CompletedProcess[str]]
PublicIPAddress = ipaddress.IPv4Address | ipaddress.IPv6Address
PTERODACTYL_HTTP_PORT = 8080
PTERODACTYL_DJ_PORT = 25808


def _run_command(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # nosec B603 - arguments are internally constructed, never shell strings
        list(arguments),
        check=True,
        capture_output=True,
        text=True,
    )


def parse_public_ip(value: str) -> PublicIPAddress:
    """Parse a globally routable public IP for a Pterodactyl identity."""
    try:
        address = ipaddress.ip_address(value.strip())
    except (AttributeError, ValueError) as exc:
        raise BootstrapError("MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address") from exc

    has_scope = isinstance(address, ipaddress.IPv6Address) and address.scope_id is not None
    if (
        not address.is_global
        or address.is_unspecified
        or address.is_loopback
        or address.is_multicast
        or address.is_link_local
        or address.is_private
        or has_scope
    ):
        raise BootstrapError("MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address")
    return address


def certificate_covers_ip(
    certificate: Path,
    public_ip: PublicIPAddress,
    *,
    command_runner: CommandRunner = _run_command,
) -> bool:
    """Return whether OpenSSL verifies the certificate's SAN for ``public_ip``."""
    try:
        result = command_runner(
            [
                "openssl",
                "x509",
                "-checkip",
                str(public_ip),
                "-noout",
                "-in",
                str(certificate),
            ]
        )
    except subprocess.CalledProcessError:
        return False
    except (OSError, subprocess.SubprocessError) as exc:
        raise BootstrapError("TLS certificate inspection failed") from exc
    expected_output = f"IP {public_ip} does match certificate"
    return result.returncode == 0 and result.stdout.strip() == expected_output


def _validate_pterodactyl_topology(
    http_port: int,
    dj_port: int,
    unified_web: bool,
) -> None:
    if http_port != PTERODACTYL_HTTP_PORT or dj_port != PTERODACTYL_DJ_PORT or not unified_web:
        raise BootstrapError("Pterodactyl requires HTTP 8080, DJ 25808, and unified web")


@contextmanager
def _deployment_lock(paths: BootstrapPaths) -> Iterator[None]:
    """Serialize identity checks and updates across processes and threads."""
    try:
        import fcntl
    except ImportError as exc:  # pragma: no cover - Pterodactyl runtime is Linux
        raise BootstrapError("Pterodactyl bootstrap requires POSIX file locking") from exc

    paths.state_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(paths.state_dir, 0o700)
    flags = os.O_CREAT | os.O_RDWR
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        file_descriptor = os.open(paths.identity_lock, flags, 0o600)
        if not stat.S_ISREG(os.fstat(file_descriptor).st_mode):
            raise OSError("deployment lock is not a regular file")
        os.fchmod(file_descriptor, 0o600)
        fcntl.flock(file_descriptor, fcntl.LOCK_EX)
    except OSError as exc:
        if "file_descriptor" in locals():
            os.close(file_descriptor)
        raise BootstrapError("Could not acquire the deployment identity lock") from exc

    try:
        yield
    finally:
        fcntl.flock(file_descriptor, fcntl.LOCK_UN)
        os.close(file_descriptor)


def _fsync_directory(path: Path) -> None:
    directory_descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory_descriptor)
    finally:
        os.close(directory_descriptor)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _atomic_write(path: Path, content: bytes, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(file_descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _atomic_copy(source: Path, destination: Path, mode: int = 0o644) -> None:
    _atomic_write(destination, source.read_bytes(), mode)


def _identity_entrypoints(paths: BootstrapPaths) -> dict[Path, str]:
    return {
        paths.runtime_env: "current-identity/runtime.env",
        paths.auth_file: "current-identity/dj_auth.json",
        paths.tls_cert: "current-identity/tls.crt",
        paths.tls_key: "current-identity/tls.key",
        paths.identity_metadata: "current-identity/identity.json",
        paths.first_login: "state/current-identity/FIRST_LOGIN.txt",
    }


def _is_expected_symlink(path: Path, target: str) -> bool:
    return path.is_symlink() and os.readlink(path) == target


def _current_identity_generation(paths: BootstrapPaths) -> Path | None:
    pointer = paths.identity_pointer
    if not pointer.is_symlink():
        if os.path.lexists(pointer):
            raise BootstrapError(f"Invalid deployment identity pointer: {pointer}")
        return None

    target = Path(os.readlink(pointer))
    if target.is_absolute():
        raise BootstrapError(f"Invalid deployment identity pointer: {pointer}")
    try:
        generation = (pointer.parent / target).resolve(strict=True)
    except OSError as exc:
        raise BootstrapError(f"Invalid deployment identity pointer: {pointer}") from exc
    generations_root = paths.identity_generations.resolve()
    if generation.parent != generations_root or not generation.is_dir():
        raise BootstrapError(f"Invalid deployment identity pointer: {pointer}")
    return generation


def _validate_or_prepare_entrypoints(paths: BootstrapPaths) -> None:
    for entrypoint, target in _identity_entrypoints(paths).items():
        if _is_expected_symlink(entrypoint, target):
            continue
        if os.path.lexists(entrypoint):
            raise BootstrapError(
                f"Refusing non-transactional or partial deployment identity: {entrypoint}"
            )

        entrypoint.parent.mkdir(parents=True, exist_ok=True)
        temporary_link = entrypoint.parent / f".{entrypoint.name}.{secrets.token_hex(8)}"
        try:
            os.symlink(target, temporary_link, target_is_directory=False)
            os.replace(temporary_link, entrypoint)
            _fsync_directory(entrypoint.parent)
        except OSError as exc:
            raise BootstrapError("Failed to prepare deployment identity entrypoints") from exc
        finally:
            temporary_link.unlink(missing_ok=True)


def _persist_identity_generation(
    paths: BootstrapPaths,
    files: Mapping[str, tuple[bytes, int]],
) -> Path:
    paths.identity_generations.mkdir(parents=True, exist_ok=True)
    os.chmod(paths.identity_generations, 0o700)
    generation_name = f"{datetime.now(UTC).strftime('%Y%m%dT%H%M%S.%fZ')}-{secrets.token_hex(8)}"
    generation = paths.identity_generations / generation_name
    staging = Path(tempfile.mkdtemp(prefix=".generation-", dir=paths.identity_generations))
    published = False
    try:
        os.chmod(staging, 0o700)
        for filename, (content, mode) in files.items():
            _atomic_write(staging / filename, content, mode)
        _fsync_directory(staging)
        os.replace(staging, generation)
        published = True
        _fsync_directory(paths.identity_generations)
    except OSError as exc:
        raise BootstrapError("Failed to persist immutable identity generation") from exc
    finally:
        if not published:
            shutil.rmtree(staging, ignore_errors=True)
    return generation


def _commit_identity_generation(
    paths: BootstrapPaths,
    files: Mapping[str, tuple[bytes, int]],
) -> Path:
    generation = _persist_identity_generation(paths, files)
    _validate_or_prepare_entrypoints(paths)
    pointer_target = generation.relative_to(paths.state_dir)
    temporary_pointer = paths.state_dir / f".current-identity.{secrets.token_hex(8)}"
    try:
        os.symlink(pointer_target, temporary_pointer, target_is_directory=True)
        os.replace(temporary_pointer, paths.identity_pointer)
        _fsync_directory(paths.state_dir)
    except OSError as exc:
        raise BootstrapError("Failed to commit immutable identity generation") from exc
    finally:
        temporary_pointer.unlink(missing_ok=True)
    return generation


def _plugin_name(jar_path: Path) -> str | None:
    try:
        with zipfile.ZipFile(jar_path) as archive:
            descriptor_name = next(
                (name for name in ("paper-plugin.yml", "plugin.yml") if name in archive.namelist()),
                None,
            )
            if descriptor_name is None:
                return None
            descriptor = archive.read(descriptor_name).decode("utf-8", errors="strict")
    except (OSError, UnicodeError, zipfile.BadZipFile):
        return None

    for line in descriptor.splitlines():
        match = re.match(r"^\s*name\s*:\s*['\"]?([^'\"#]+)", line)
        if match:
            return match.group(1).strip()
    return None


def _validate_release(paths: BootstrapPaths) -> None:
    if not paths.release_jar.is_file():
        raise BootstrapError(f"Release plugin JAR is missing: {paths.release_jar}")
    if _plugin_name(paths.release_jar) != "AudioViz":
        raise BootstrapError(f"Release JAR is not the AudioViz plugin: {paths.release_jar}")
    if not paths.default_config.is_file():
        raise BootstrapError(f"Default plugin configuration is missing: {paths.default_config}")


def _normalize_fingerprint(output: str) -> str:
    fingerprint = re.sub(r"[:\s]", "", output.split("=", 1)[-1]).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise BootstrapError("OpenSSL returned an invalid TLS SHA-256 fingerprint")
    return fingerprint


def _certificate_fingerprint(
    certificate: Path,
    command_runner: CommandRunner,
) -> str:
    try:
        output = command_runner(
            [
                "openssl",
                "x509",
                "-fingerprint",
                "-sha256",
                "-noout",
                "-in",
                str(certificate),
            ]
        ).stdout
    except (OSError, subprocess.SubprocessError) as exc:
        raise BootstrapError("TLS certificate fingerprint inspection failed") from exc
    return _normalize_fingerprint(output)


def _identity_metadata(public_ip: PublicIPAddress, fingerprint: str) -> bytes:
    metadata = {
        "schema": 1,
        "public_host": str(public_ip),
        "sha256_fingerprint": fingerprint,
        "http_port": PTERODACTYL_HTTP_PORT,
        "dj_port": PTERODACTYL_DJ_PORT,
        "unified_web": True,
    }
    return (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _regular_identity_file(generation: Path, filename: str) -> Path:
    path = generation / filename
    try:
        file_status = path.lstat()
    except OSError as exc:
        raise ValueError(f"{filename} is missing") from exc
    if not stat.S_ISREG(file_status.st_mode):
        raise ValueError(f"{filename} is not a regular file")
    return path


def _login_values(content: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in content.splitlines():
        if "=" in line:
            field, value = line.split("=", 1)
            values[field] = value
    return values


def _validate_existing_identity(
    paths: BootstrapPaths,
    generation: Path,
    command_runner: CommandRunner,
) -> tuple[str, PublicIPAddress]:
    try:
        runtime = _regular_identity_file(generation, "runtime.env")
        auth_file = _regular_identity_file(generation, "dj_auth.json")
        certificate = _regular_identity_file(generation, "tls.crt")
        private_key = _regular_identity_file(generation, "tls.key")
        first_login = _regular_identity_file(generation, "FIRST_LOGIN.txt")
        metadata_file = _regular_identity_file(generation, "identity.json")

        key_mode = stat.S_IMODE(private_key.stat().st_mode)
        if key_mode != 0o600:
            os.chmod(private_key, 0o600)
        if stat.S_IMODE(private_key.stat().st_mode) != 0o600:
            raise ValueError("tls.key must be owner-only (0600)")

        runtime_text = runtime.read_text(encoding="utf-8")
        secret_match = re.fullmatch(r"MINECRAFT_WS_SECRET=([^\s]+)\n?", runtime_text)
        if not secret_match or len(secret_match.group(1)) < 32:
            raise ValueError("missing or short MINECRAFT_WS_SECRET")

        auth_data = json.loads(auth_file.read_text(encoding="utf-8"))
        if not auth_data.get("djs") or not auth_data.get("vj_operators"):
            raise ValueError("DJ or administrator identity is missing")
        for section_name in ("djs", "vj_operators"):
            for entry in auth_data[section_name].values():
                if not str(entry.get("key_hash", "")).startswith("bcrypt:"):
                    raise ValueError(f"{section_name} contains a non-bcrypt credential")

        metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
        expected_topology = {
            "schema": 1,
            "http_port": PTERODACTYL_HTTP_PORT,
            "dj_port": PTERODACTYL_DJ_PORT,
            "unified_web": True,
        }
        if any(metadata.get(field) != value for field, value in expected_topology.items()):
            raise ValueError("identity metadata has an invalid Pterodactyl topology")
        identity_public_ip = parse_public_ip(metadata.get("public_host", ""))
        fingerprint = str(metadata.get("sha256_fingerprint", ""))
        if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
            raise ValueError("identity metadata has an invalid TLS fingerprint")

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certificate, private_key)
        if _certificate_fingerprint(certificate, command_runner) != fingerprint:
            raise ValueError("certificate fingerprint does not match identity metadata")

        login = _login_values(first_login.read_text(encoding="utf-8"))
        for field in ("ADMIN_USERNAME", "ADMIN_PASSWORD", "DJ_USERNAME", "DJ_PASSWORD"):
            if not login.get(field):
                raise ValueError(f"{field} is missing")
        expected_endpoints = _endpoint_values(identity_public_ip, fingerprint)
        if any(login.get(field) != value for field, value in expected_endpoints.items()):
            raise ValueError("first-login endpoint metadata is inconsistent")
        return secret_match.group(1), identity_public_ip
    except BootstrapError:
        raise
    except (OSError, ValueError, TypeError, json.JSONDecodeError, ssl.SSLError) as exc:
        raise BootstrapError(f"Invalid deployment identity at {paths.state_dir}: {exc}") from exc


def _generate_tls_material(
    paths: BootstrapPaths,
    public_ip: PublicIPAddress,
    command_runner: CommandRunner,
) -> tuple[bytes, bytes, str]:
    paths.state_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(paths.state_dir, 0o700)
    with tempfile.TemporaryDirectory(prefix=".bootstrap-", dir=paths.state_dir) as temporary:
        staging = Path(temporary)
        staged_key = staging / "tls.key"
        staged_cert = staging / "tls.crt"
        try:
            command_runner(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "ec",
                    "-pkeyopt",
                    "ec_paramgen_curve:P-256",
                    "-sha256",
                    "-days",
                    "397",
                    "-nodes",
                    "-subj",
                    "/CN=MCAV Control Center",
                    "-addext",
                    f"subjectAltName=IP:{public_ip},DNS:localhost,IP:127.0.0.1",
                    "-keyout",
                    str(staged_key),
                    "-out",
                    str(staged_cert),
                ]
            )
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(staged_cert, staged_key)
            if not certificate_covers_ip(
                staged_cert,
                public_ip,
                command_runner=command_runner,
            ):
                raise BootstrapError("Generated TLS certificate does not cover MCAV_PUBLIC_HOST")
            try:
                fingerprint = _certificate_fingerprint(staged_cert, command_runner)
            except BootstrapError as exc:
                raise BootstrapError(f"TLS identity generation failed: {exc}") from exc
            certificate = staged_cert.read_bytes()
            private_key = staged_key.read_bytes()
        except BootstrapError:
            raise
        except (OSError, subprocess.SubprocessError, ssl.SSLError) as exc:
            raise BootstrapError(f"TLS identity generation failed in {paths.state_dir}") from exc
    return certificate, private_key, fingerprint


def _endpoint_values(public_ip: PublicIPAddress, fingerprint: str) -> dict[str, str]:
    endpoint_host = (
        f"[{public_ip}]" if isinstance(public_ip, ipaddress.IPv6Address) else str(public_ip)
    )
    return {
        "TLS_SHA256_FINGERPRINT": fingerprint,
        "ADMIN_URL": f"https://{endpoint_host}:{PTERODACTYL_HTTP_PORT}/",
        "PREVIEW_URL": f"https://{endpoint_host}:{PTERODACTYL_HTTP_PORT}/preview/",
        "DJ_ENDPOINT": f"wss://{endpoint_host}:{PTERODACTYL_DJ_PORT}",
    }


def _create_first_login(
    release_version: str,
    admin_username: str,
    admin_password: str,
    dj_username: str,
    dj_password: str,
    public_ip: PublicIPAddress,
    fingerprint: str,
) -> bytes:
    endpoint_values = _endpoint_values(public_ip, fingerprint)
    return (
        "MCAV FIRST LOGIN - KEEP THIS FILE PRIVATE\n"
        f"RELEASE={release_version}\n"
        f"ADMIN_USERNAME={admin_username}\n"
        f"ADMIN_PASSWORD={admin_password}\n"
        f"DJ_USERNAME={dj_username}\n"
        f"DJ_PASSWORD={dj_password}\n"
        f"TLS_SHA256_FINGERPRINT={endpoint_values['TLS_SHA256_FINGERPRINT']}\n"
        f"ADMIN_URL={endpoint_values['ADMIN_URL']}\n"
        f"PREVIEW_URL={endpoint_values['PREVIEW_URL']}\n"
        f"DJ_ENDPOINT={endpoint_values['DJ_ENDPOINT']}\n"
    ).encode("utf-8")


def _update_first_login(
    content: str,
    public_ip: PublicIPAddress,
    fingerprint: str,
) -> bytes:
    endpoint_values = _endpoint_values(public_ip, fingerprint)
    output: list[str] = []
    updated_fields: set[str] = set()
    for line in content.splitlines():
        field = line.split("=", 1)[0]
        if field in endpoint_values:
            output.append(f"{field}={endpoint_values[field]}")
            updated_fields.add(field)
        else:
            output.append(line)
    for field, value in endpoint_values.items():
        if field not in updated_fields:
            output.append(f"{field}={value}")
    return ("\n".join(output) + "\n").encode("utf-8")


def _create_identity(
    paths: BootstrapPaths,
    release_version: str,
    public_ip: PublicIPAddress,
    command_runner: CommandRunner,
) -> str:
    certificate, private_key, fingerprint = _generate_tls_material(
        paths,
        public_ip,
        command_runner,
    )
    shared_secret = secrets.token_urlsafe(32)
    admin_username = f"mcav-admin-{secrets.token_hex(3)}"
    admin_password = secrets.token_urlsafe(24)
    dj_username = f"mcav-dj-{secrets.token_hex(3)}"
    dj_password = secrets.token_urlsafe(24)

    auth_data = {
        "djs": {
            dj_username: {
                "name": "MCAV DJ",
                "key_hash": hash_password(dj_password, "bcrypt"),
                "priority": 10,
            }
        },
        "vj_operators": {
            admin_username: {
                "name": "MCAV Administrator",
                "key_hash": hash_password(admin_password, "bcrypt"),
            }
        },
    }
    first_login = _create_first_login(
        release_version,
        admin_username,
        admin_password,
        dj_username,
        dj_password,
        public_ip,
        fingerprint,
    )
    _commit_identity_generation(
        paths,
        {
            "runtime.env": (f"MINECRAFT_WS_SECRET={shared_secret}\n".encode(), 0o600),
            "dj_auth.json": ((json.dumps(auth_data, indent=2) + "\n").encode(), 0o600),
            "tls.crt": (certificate, 0o644),
            "tls.key": (private_key, 0o600),
            "FIRST_LOGIN.txt": (first_login, 0o600),
            "identity.json": (_identity_metadata(public_ip, fingerprint), 0o600),
        },
    )
    return shared_secret


def _rotation_command(paths: BootstrapPaths, public_ip: PublicIPAddress) -> str:
    return shlex.join(
        [
            "audioviz-vj",
            "--bootstrap-pterodactyl",
            "--project-root",
            str(paths.project_root),
            "--plugins-dir",
            str(paths.plugins_dir),
            "--public-host",
            str(public_ip),
            "--http-port",
            str(PTERODACTYL_HTTP_PORT),
            "--port",
            str(PTERODACTYL_DJ_PORT),
            "--unified-web",
            "--rotate-tls-identity",
        ]
    )


def _rotate_tls_identity(
    paths: BootstrapPaths,
    generation: Path,
    public_ip: PublicIPAddress,
    command_runner: CommandRunner,
) -> None:
    certificate, private_key, fingerprint = _generate_tls_material(
        paths,
        public_ip,
        command_runner,
    )
    login_content = (generation / "FIRST_LOGIN.txt").read_text(encoding="utf-8")
    updated_login = _update_first_login(login_content, public_ip, fingerprint)
    try:
        runtime_env = (generation / "runtime.env").read_bytes()
        auth_file = (generation / "dj_auth.json").read_bytes()
    except OSError as exc:
        raise BootstrapError("Existing deployment identity changed during rotation") from exc
    _commit_identity_generation(
        paths,
        {
            "runtime.env": (runtime_env, 0o600),
            "dj_auth.json": (auth_file, 0o600),
            "tls.crt": (certificate, 0o644),
            "tls.key": (private_key, 0o600),
            "FIRST_LOGIN.txt": (updated_login, 0o600),
            "identity.json": (_identity_metadata(public_ip, fingerprint), 0o600),
        },
    )


def _ensure_identity(
    paths: BootstrapPaths,
    release_version: str,
    public_ip: PublicIPAddress,
    rotate_tls_identity: bool,
    command_runner: CommandRunner,
) -> tuple[str, bool]:
    generation = _current_identity_generation(paths)
    if generation is None:
        unexpected_entrypoints = [
            entrypoint
            for entrypoint, target in _identity_entrypoints(paths).items()
            if os.path.lexists(entrypoint) and not _is_expected_symlink(entrypoint, target)
        ]
        if unexpected_entrypoints:
            present = ", ".join(str(path) for path in unexpected_entrypoints)
            raise BootstrapError(f"Refusing partial deployment identity; present files: {present}")
        if rotate_tls_identity:
            raise BootstrapError(
                "--rotate-tls-identity requires an existing complete deployment identity"
            )
        return _create_identity(paths, release_version, public_ip, command_runner), True

    shared_secret, identity_public_ip = _validate_existing_identity(
        paths,
        generation,
        command_runner,
    )
    _validate_or_prepare_entrypoints(paths)
    if rotate_tls_identity:
        _rotate_tls_identity(paths, generation, public_ip, command_runner)
    elif identity_public_ip != public_ip or not certificate_covers_ip(
        generation / "tls.crt", public_ip, command_runner=command_runner
    ):
        raise BootstrapError(
            "Existing TLS certificate does not cover MCAV_PUBLIC_HOST. "
            f"Rotate it explicitly with: {_rotation_command(paths, public_ip)}"
        )
    return shared_secret, False


def _new_backup_dir(paths: BootstrapPaths) -> Path:
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S.%fZ")
    backup_dir = paths.backups_dir / timestamp
    backup_dir.mkdir(parents=True, exist_ok=False)
    return backup_dir


def _install_plugin(paths: BootstrapPaths) -> tuple[bool, Path | None]:
    paths.plugins_dir.mkdir(parents=True, exist_ok=True)
    matching_plugins = [
        path for path in paths.plugins_dir.glob("*.jar") if _plugin_name(path) == "AudioViz"
    ]
    canonical = paths.plugins_dir / "AudioViz.jar"
    release_digest = _sha256(paths.release_jar)
    if canonical.is_file() and _sha256(canonical) == release_digest and len(matching_plugins) == 1:
        return False, None

    backup_dir = _new_backup_dir(paths) if matching_plugins else None
    if backup_dir:
        for index, plugin in enumerate(matching_plugins):
            backup_name = plugin.name
            if (backup_dir / backup_name).exists():
                backup_name = f"{plugin.stem}-{index}{plugin.suffix}"
            shutil.copy2(plugin, backup_dir / backup_name)

    _atomic_copy(paths.release_jar, canonical, 0o644)
    for plugin in matching_plugins:
        if plugin != canonical and plugin.exists():
            destination = backup_dir / plugin.name
            if destination.exists():
                destination = backup_dir / f"old-{plugin.name}"
            os.replace(plugin, destination)
    return True, backup_dir


def _patch_plugin_config(content: str, shared_secret: str) -> str:
    lines = content.splitlines(keepends=True)
    newline = "\r\n" if "\r\n" in content else "\n"
    in_websocket = False
    saw_websocket = False
    saw_address = False
    saw_port = False
    saw_secret = False
    output: list[str] = []

    for line in lines:
        stripped = line.strip()
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0 and stripped and not stripped.startswith("#"):
            if in_websocket:
                if not saw_address:
                    output.append(f'  address: "127.0.0.1"{newline}')
                if not saw_port:
                    output.append(f"  port: 8765{newline}")
            in_websocket = stripped.startswith("websocket:")
            if in_websocket:
                saw_websocket = True

        if in_websocket and indent > 0:
            if re.match(r"^\s*address\s*:", line):
                output.append(f'  address: "127.0.0.1"{newline}')
                saw_address = True
                continue
            if re.match(r"^\s*port\s*:", line):
                output.append(f"  port: 8765{newline}")
                saw_port = True
                continue
        if indent == 0 and re.match(r"^ws-secret\s*:", stripped):
            output.append(f'ws-secret: "{shared_secret}"{newline}')
            saw_secret = True
            continue
        output.append(line)

    if in_websocket:
        if not saw_address:
            output.append(f'  address: "127.0.0.1"{newline}')
        if not saw_port:
            output.append(f"  port: 8765{newline}")
    if not saw_websocket:
        output.append(f'websocket:{newline}  address: "127.0.0.1"{newline}  port: 8765{newline}')
    if not saw_secret:
        output.append(f'ws-secret: "{shared_secret}"{newline}')
    return "".join(output)


def _configure_plugin(
    paths: BootstrapPaths,
    shared_secret: str,
    backup_dir: Path | None,
) -> tuple[bool, Path | None]:
    paths.plugin_config.parent.mkdir(parents=True, exist_ok=True)
    source = paths.plugin_config if paths.plugin_config.exists() else paths.default_config
    original = source.read_text(encoding="utf-8")
    patched = _patch_plugin_config(original, shared_secret)
    if paths.plugin_config.exists() and patched == original:
        return False, backup_dir

    if paths.plugin_config.exists():
        backup_dir = backup_dir or _new_backup_dir(paths)
        shutil.copy2(paths.plugin_config, backup_dir / "config.yml")
    _atomic_write(paths.plugin_config, patched.encode("utf-8"), 0o600)
    return True, backup_dir


def bootstrap_pterodactyl(
    paths: BootstrapPaths,
    release_version: str,
    *,
    public_host: str | None = None,
    rotate_tls_identity: bool = False,
    http_port: int = PTERODACTYL_HTTP_PORT,
    dj_port: int = PTERODACTYL_DJ_PORT,
    unified_web: bool = True,
    command_runner: CommandRunner = _run_command,
) -> BootstrapResult:
    """Bootstrap persistent identity, plugin, and loopback renderer configuration."""
    paths = BootstrapPaths(paths.project_root.resolve(), paths.plugins_dir.resolve())
    if public_host is None:
        raise BootstrapError("MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address")
    public_ip = parse_public_ip(public_host)
    _validate_pterodactyl_topology(http_port, dj_port, unified_web)

    with _deployment_lock(paths):
        if rotate_tls_identity:
            _, credentials_created = _ensure_identity(
                paths,
                release_version,
                public_ip,
                True,
                command_runner,
            )
            return BootstrapResult(
                credentials_created=credentials_created,
                plugin_installed=False,
                config_updated=False,
                first_login=paths.first_login,
                auth_file=paths.auth_file,
                tls_cert=paths.tls_cert,
            )

        _validate_release(paths)
        paths.backups_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(paths.backups_dir, 0o700)
        shared_secret, credentials_created = _ensure_identity(
            paths,
            release_version,
            public_ip,
            False,
            command_runner,
        )
        plugin_installed, backup_dir = _install_plugin(paths)
        config_updated, _ = _configure_plugin(paths, shared_secret, backup_dir)

        return BootstrapResult(
            credentials_created=credentials_created,
            plugin_installed=plugin_installed,
            config_updated=config_updated,
            first_login=paths.first_login,
            auth_file=paths.auth_file,
            tls_cert=paths.tls_cert,
        )
