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
import subprocess  # nosec B404 - fixed OpenSSL argument vectors; shell execution is never used
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable, Sequence

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
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary_path, mode)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _atomic_copy(source: Path, destination: Path, mode: int = 0o644) -> None:
    _atomic_write(destination, source.read_bytes(), mode)


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


def _validate_existing_identity(paths: BootstrapPaths) -> str:
    try:
        runtime_text = paths.runtime_env.read_text(encoding="utf-8")
        secret_match = re.fullmatch(r"MINECRAFT_WS_SECRET=([^\s]+)\n?", runtime_text)
        if not secret_match or len(secret_match.group(1)) < 32:
            raise ValueError("missing or short MINECRAFT_WS_SECRET")

        auth_data = json.loads(paths.auth_file.read_text(encoding="utf-8"))
        if not auth_data.get("djs") or not auth_data.get("vj_operators"):
            raise ValueError("DJ or administrator identity is missing")
        for section_name in ("djs", "vj_operators"):
            for entry in auth_data[section_name].values():
                if not str(entry.get("key_hash", "")).startswith("bcrypt:"):
                    raise ValueError(f"{section_name} contains a non-bcrypt credential")

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(paths.tls_cert, paths.tls_key)
        login_text = paths.first_login.read_text(encoding="utf-8")
        for field in ("ADMIN_USERNAME=", "ADMIN_PASSWORD=", "DJ_USERNAME=", "DJ_PASSWORD="):
            if field not in login_text:
                raise ValueError(f"{field[:-1]} is missing")
        return secret_match.group(1)
    except (OSError, ValueError, TypeError, json.JSONDecodeError, ssl.SSLError) as exc:
        raise BootstrapError(f"Invalid deployment identity at {paths.state_dir}: {exc}") from exc


def _normalize_fingerprint(output: str) -> str:
    fingerprint = re.sub(r"[:\s]", "", output.split("=", 1)[-1]).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise BootstrapError("OpenSSL returned an invalid TLS SHA-256 fingerprint")
    return fingerprint


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
            fingerprint_output = command_runner(
                [
                    "openssl",
                    "x509",
                    "-fingerprint",
                    "-sha256",
                    "-noout",
                    "-in",
                    str(staged_cert),
                ]
            ).stdout
            fingerprint = _normalize_fingerprint(fingerprint_output)
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
        "ADMIN_URL": f"https://{endpoint_host}:8080/",
        "PREVIEW_URL": f"https://{endpoint_host}:8080/preview/",
        "DJ_ENDPOINT": f"wss://{endpoint_host}:25808",
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
    identity_paths = (
        paths.runtime_env,
        paths.auth_file,
        paths.tls_cert,
        paths.tls_key,
        paths.first_login,
    )
    try:
        _atomic_write(paths.runtime_env, f"MINECRAFT_WS_SECRET={shared_secret}\n".encode(), 0o600)
        _atomic_write(paths.auth_file, (json.dumps(auth_data, indent=2) + "\n").encode(), 0o600)
        _atomic_write(paths.tls_cert, certificate, 0o644)
        _atomic_write(paths.tls_key, private_key, 0o600)
        _atomic_write(paths.first_login, first_login, 0o600)
    except OSError as exc:
        for identity_path in identity_paths:
            identity_path.unlink(missing_ok=True)
        raise BootstrapError("Failed to persist the new deployment identity") from exc
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
            "--rotate-tls-identity",
        ]
    )


def _rotate_tls_identity(
    paths: BootstrapPaths,
    public_ip: PublicIPAddress,
    command_runner: CommandRunner,
) -> None:
    certificate, private_key, fingerprint = _generate_tls_material(
        paths,
        public_ip,
        command_runner,
    )
    login_content = paths.first_login.read_text(encoding="utf-8")
    updated_login = _update_first_login(login_content, public_ip, fingerprint)
    original_files = {
        paths.tls_key: (paths.tls_key.read_bytes(), 0o600),
        paths.tls_cert: (paths.tls_cert.read_bytes(), 0o644),
        paths.first_login: (paths.first_login.read_bytes(), 0o600),
    }
    try:
        _atomic_write(paths.tls_key, private_key, 0o600)
        _atomic_write(paths.tls_cert, certificate, 0o644)
        _atomic_write(paths.first_login, updated_login, 0o600)
    except OSError as exc:
        try:
            for path, (content, mode) in original_files.items():
                _atomic_write(path, content, mode)
        except OSError as rollback_error:
            raise BootstrapError("TLS identity rotation and recovery failed") from rollback_error
        raise BootstrapError(
            "TLS identity rotation failed; the original identity was restored"
        ) from exc


def _ensure_identity(
    paths: BootstrapPaths,
    release_version: str,
    public_ip: PublicIPAddress,
    rotate_tls_identity: bool,
    command_runner: CommandRunner,
) -> tuple[str, bool]:
    identity_paths = (
        paths.runtime_env,
        paths.auth_file,
        paths.tls_cert,
        paths.tls_key,
        paths.first_login,
    )
    existing_count = sum(path.exists() for path in identity_paths)
    if existing_count == 0:
        if rotate_tls_identity:
            raise BootstrapError(
                "--rotate-tls-identity requires an existing complete deployment identity"
            )
        return _create_identity(paths, release_version, public_ip, command_runner), True
    if existing_count != len(identity_paths):
        present = ", ".join(str(path) for path in identity_paths if path.exists())
        raise BootstrapError(f"Refusing partial deployment identity; present files: {present}")

    shared_secret = _validate_existing_identity(paths)
    if rotate_tls_identity:
        _rotate_tls_identity(paths, public_ip, command_runner)
    elif not certificate_covers_ip(
        paths.tls_cert,
        public_ip,
        command_runner=command_runner,
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
    command_runner: CommandRunner = _run_command,
) -> BootstrapResult:
    """Bootstrap persistent identity, plugin, and loopback renderer configuration."""
    paths = BootstrapPaths(paths.project_root.resolve(), paths.plugins_dir.resolve())
    if public_host is None:
        raise BootstrapError("MCAV_PUBLIC_HOST must be a public IPv4 or IPv6 address")
    public_ip = parse_public_ip(public_host)
    _validate_release(paths)
    paths.backups_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(paths.backups_dir, 0o700)

    shared_secret, credentials_created = _ensure_identity(
        paths,
        release_version,
        public_ip,
        rotate_tls_identity,
        command_runner,
    )
    if rotate_tls_identity:
        return BootstrapResult(
            credentials_created=credentials_created,
            plugin_installed=False,
            config_updated=False,
            first_login=paths.first_login,
            auth_file=paths.auth_file,
            tls_cert=paths.tls_cert,
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
