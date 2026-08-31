"""Portable TLS identity generation and validation for managed MCAV runtimes."""

from __future__ import annotations

import hashlib
import ipaddress
import os
import re
import secrets
import tempfile
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


class TlsIdentityError(RuntimeError):
    """The configured TLS identity is incomplete or invalid."""


@dataclass(frozen=True, slots=True)
class TlsIdentity:
    certificate_path: Path
    private_key_path: Path
    fingerprint: str
    expires_at: datetime
    generated: bool


class TlsManager:
    """Create or validate one persistent server certificate and private key."""

    def __init__(
        self,
        state_directory: Path,
        *,
        public_names: Iterable[str] = (),
        supplied_certificate: Path | None = None,
        supplied_private_key: Path | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if bool(supplied_certificate) != bool(supplied_private_key):
            raise TlsIdentityError("TLS certificate and private key must be supplied together")
        self.state_directory = state_directory.absolute()
        self.certificate_path = (
            supplied_certificate.absolute()
            if supplied_certificate is not None
            else self.state_directory / "tls.crt"
        )
        self.private_key_path = (
            supplied_private_key.absolute()
            if supplied_private_key is not None
            else self.state_directory / "tls.key"
        )
        self._generated = supplied_certificate is None
        self._public_names = _normalize_public_names(public_names)
        self._clock = clock or (lambda: datetime.now(UTC))

    @property
    def generated(self) -> bool:
        return self._generated

    def ensure(self) -> TlsIdentity:
        certificate_exists = self.certificate_path.is_file()
        key_exists = self.private_key_path.is_file()
        if certificate_exists != key_exists:
            raise TlsIdentityError("partial TLS identity; certificate and key must both exist")

        if not certificate_exists:
            if not self._generated:
                raise TlsIdentityError("supplied TLS certificate or private key is missing")
            self._generate()

        identity = self._validate_pair()
        if self._generated:
            _set_mode(self.private_key_path, 0o600)
            _set_mode(self.certificate_path, 0o644)
        return identity

    def _generate(self) -> None:
        self.state_directory.mkdir(parents=True, exist_ok=True)
        private_key = ec.generate_private_key(ec.SECP256R1())
        now = _utc(self._clock())
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "MCAV Control Center")])
        serial = secrets.randbits(128) or 1
        certificate = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(private_key.public_key())
            .serial_number(serial)
            .not_valid_before(now - timedelta(minutes=5))
            .not_valid_after(now + timedelta(days=397))
            .add_extension(
                x509.BasicConstraints(ca=False, path_length=None),
                critical=True,
            )
            .add_extension(
                x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),
                critical=False,
            )
            .add_extension(
                x509.SubjectAlternativeName(_subject_alt_names(self._public_names)),
                critical=False,
            )
            .sign(private_key, hashes.SHA256())
        )
        key_bytes = private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        certificate_bytes = certificate.public_bytes(serialization.Encoding.PEM)
        _atomic_write(self.private_key_path, key_bytes, 0o600)
        _atomic_write(self.certificate_path, certificate_bytes, 0o644)

    def _validate_pair(self) -> TlsIdentity:
        try:
            certificate_bytes = self.certificate_path.read_bytes()
            key_bytes = self.private_key_path.read_bytes()
            certificate = x509.load_pem_x509_certificate(certificate_bytes)
            private_key = serialization.load_pem_private_key(key_bytes, password=None)
        except (OSError, TypeError, ValueError) as error:
            raise TlsIdentityError("TLS certificate or private key could not be loaded") from error

        certificate_public_key = certificate.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        private_public_key = private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        if certificate_public_key != private_public_key:
            raise TlsIdentityError("TLS private key does not match the certificate")

        now = _utc(self._clock())
        if not certificate.not_valid_before_utc <= now <= certificate.not_valid_after_utc:
            raise TlsIdentityError("TLS certificate is not currently valid")
        try:
            extended_usage = certificate.extensions.get_extension_for_class(
                x509.ExtendedKeyUsage
            ).value
        except x509.ExtensionNotFound:
            extended_usage = None
        if extended_usage is not None and ExtendedKeyUsageOID.SERVER_AUTH not in extended_usage:
            raise TlsIdentityError("TLS certificate does not permit server authentication")

        if self._public_names:
            try:
                subject_names = certificate.extensions.get_extension_for_class(
                    x509.SubjectAlternativeName
                ).value
            except x509.ExtensionNotFound as error:
                raise TlsIdentityError(
                    "TLS certificate does not contain the configured public name"
                ) from error
            if not _contains_all_public_names(subject_names, self._public_names):
                raise TlsIdentityError(
                    "TLS certificate does not contain the configured public name"
                )

        fingerprint = hashlib.sha256(
            certificate.public_bytes(serialization.Encoding.DER)
        ).hexdigest()
        return TlsIdentity(
            certificate_path=self.certificate_path,
            private_key_path=self.private_key_path,
            fingerprint=fingerprint.upper(),
            expires_at=certificate.not_valid_after_utc,
            generated=self._generated,
        )


def _normalize_public_names(values: Iterable[str]) -> tuple[str, ...]:
    normalized: list[str] = []
    for value in values:
        if not isinstance(value, str) or value != value.strip() or not value:
            raise ValueError("invalid TLS public name")
        try:
            name = str(ipaddress.ip_address(value))
        except ValueError:
            name = value.lower()
            if (
                len(name) > 253
                or "*" in name
                or not re.fullmatch(
                    r"(?=.{1,253}\Z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*"
                    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?",
                    name,
                )
            ):
                raise ValueError("invalid TLS public name") from None
        if name not in normalized:
            normalized.append(name)
    return tuple(normalized)


def _subject_alt_names(public_names: tuple[str, ...]) -> list[x509.GeneralName]:
    names: list[x509.GeneralName] = [
        x509.DNSName("localhost"),
        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
        x509.IPAddress(ipaddress.ip_address("::1")),
    ]
    for value in public_names:
        try:
            names.append(x509.IPAddress(ipaddress.ip_address(value)))
        except ValueError:
            if value != "localhost":
                names.append(x509.DNSName(value))
    return names


def _contains_all_public_names(
    subject_names: x509.SubjectAlternativeName,
    public_names: tuple[str, ...],
) -> bool:
    dns_names = {name.lower() for name in subject_names.get_values_for_type(x509.DNSName)}
    ip_names = set(subject_names.get_values_for_type(x509.IPAddress))
    for value in public_names:
        try:
            if ipaddress.ip_address(value) not in ip_names:
                return False
        except ValueError:
            if value.lower() not in dns_names:
                return False
    return True


def _atomic_write(path: Path, content: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as destination:
            destination.write(content)
            destination.flush()
            os.fsync(destination.fileno())
        _set_mode(temporary_path, mode)
        os.replace(temporary_path, path)
        _fsync_directory(path.parent)
    finally:
        temporary_path.unlink(missing_ok=True)


def _set_mode(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError as error:
        if os.name != "nt":
            raise TlsIdentityError(f"could not secure TLS identity permissions: {error}") from error


def _fsync_directory(directory: Path) -> None:
    try:
        descriptor = os.open(directory, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("TLS clock must return a timezone-aware datetime")
    return value.astimezone(UTC)
