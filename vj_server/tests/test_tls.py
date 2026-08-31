from __future__ import annotations

import ipaddress
import os
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from vj_server.tls import TlsIdentityError, TlsManager

NOW = datetime(2026, 8, 31, 18, 0, tzinfo=UTC)


def write_certificate_pair(
    certificate_path: Path,
    key_path: Path,
    *,
    key: ec.EllipticCurvePrivateKey | None = None,
    dns_names: tuple[str, ...] = ("panel.example.test",),
    not_before: datetime = NOW - timedelta(minutes=1),
    not_after: datetime = NOW + timedelta(days=30),
    server_auth: bool = True,
) -> ec.EllipticCurvePrivateKey:
    private_key = key or ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "MCAV Test")])
    builder = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before)
        .not_valid_after(not_after)
        .add_extension(
            x509.BasicConstraints(ca=False, path_length=None),
            critical=True,
        )
        .add_extension(
            x509.SubjectAlternativeName([x509.DNSName(name) for name in dns_names]),
            critical=False,
        )
    )
    usage = [ExtendedKeyUsageOID.SERVER_AUTH] if server_auth else [ExtendedKeyUsageOID.CLIENT_AUTH]
    certificate = builder.add_extension(
        x509.ExtendedKeyUsage(usage),
        critical=False,
    ).sign(private_key, hashes.SHA256())
    certificate_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(
        private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return private_key


def test_generated_tls_identity_is_portable_complete_and_idempotent(tmp_path: Path) -> None:
    manager = TlsManager(
        tmp_path,
        public_names=("panel.example.test", "203.0.113.9"),
        clock=lambda: NOW,
    )

    identity = manager.ensure()
    before = (identity.certificate_path.read_bytes(), identity.private_key_path.read_bytes())
    second = manager.ensure()
    certificate = x509.load_pem_x509_certificate(before[0])
    private_key = serialization.load_pem_private_key(before[1], password=None)
    sans = certificate.extensions.get_extension_for_class(x509.SubjectAlternativeName).value

    assert isinstance(private_key, ec.EllipticCurvePrivateKey)
    assert isinstance(private_key.curve, ec.SECP256R1)
    assert set(sans.get_values_for_type(x509.DNSName)) == {
        "localhost",
        "panel.example.test",
    }
    assert set(sans.get_values_for_type(x509.IPAddress)) == {
        ipaddress.ip_address("127.0.0.1"),
        ipaddress.ip_address("::1"),
        ipaddress.ip_address("203.0.113.9"),
    }
    assert certificate.not_valid_before_utc == NOW - timedelta(minutes=5)
    assert certificate.not_valid_after_utc == NOW + timedelta(days=397)
    assert certificate.extensions.get_extension_for_class(x509.BasicConstraints).value.ca is False
    assert (
        ExtendedKeyUsageOID.SERVER_AUTH
        in certificate.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
    )
    assert re.fullmatch(r"[0-9A-F]{64}", identity.fingerprint)
    assert identity.generated is True
    assert second.fingerprint == identity.fingerprint
    assert (second.certificate_path.read_bytes(), second.private_key_path.read_bytes()) == before
    if os.name != "nt":
        assert identity.private_key_path.stat().st_mode & 0o777 == 0o600
        assert identity.certificate_path.stat().st_mode & 0o777 == 0o644


@pytest.mark.parametrize("existing_name", ["tls.crt", "tls.key"])
def test_generated_tls_refuses_partial_identity(tmp_path: Path, existing_name: str) -> None:
    (tmp_path / existing_name).write_text("do-not-overwrite", encoding="utf-8")

    with pytest.raises(TlsIdentityError, match="partial TLS identity"):
        TlsManager(tmp_path, clock=lambda: NOW).ensure()

    assert (tmp_path / existing_name).read_text(encoding="utf-8") == "do-not-overwrite"


def test_supplied_tls_pair_is_validated_without_copying(tmp_path: Path) -> None:
    certificate_path = tmp_path / "provided.crt"
    key_path = tmp_path / "provided.key"
    write_certificate_pair(certificate_path, key_path)
    before = (certificate_path.read_bytes(), key_path.read_bytes())

    identity = TlsManager(
        tmp_path / "state",
        supplied_certificate=certificate_path,
        supplied_private_key=key_path,
        public_names=("panel.example.test",),
        clock=lambda: NOW,
    ).ensure()

    assert identity.generated is False
    assert (certificate_path.read_bytes(), key_path.read_bytes()) == before


def test_supplied_tls_rejects_mismatched_key(tmp_path: Path) -> None:
    certificate_path = tmp_path / "provided.crt"
    key_path = tmp_path / "provided.key"
    write_certificate_pair(certificate_path, key_path)
    other_key = ec.generate_private_key(ec.SECP256R1())
    key_path.write_bytes(
        other_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )

    with pytest.raises(TlsIdentityError, match="does not match"):
        TlsManager(
            tmp_path / "state",
            supplied_certificate=certificate_path,
            supplied_private_key=key_path,
            clock=lambda: NOW,
        ).ensure()


@pytest.mark.parametrize(
    ("certificate_options", "message"),
    [
        (
            {"not_before": NOW - timedelta(days=30), "not_after": NOW - timedelta(seconds=1)},
            "not currently valid",
        ),
        ({"server_auth": False}, "server authentication"),
        ({"dns_names": ("other.example.test",)}, "public name"),
    ],
)
def test_supplied_tls_rejects_invalid_certificate_policy(
    tmp_path: Path,
    certificate_options: dict,
    message: str,
) -> None:
    certificate_path = tmp_path / "provided.crt"
    key_path = tmp_path / "provided.key"
    write_certificate_pair(certificate_path, key_path, **certificate_options)

    with pytest.raises(TlsIdentityError, match=message):
        TlsManager(
            tmp_path / "state",
            supplied_certificate=certificate_path,
            supplied_private_key=key_path,
            public_names=("panel.example.test",),
            clock=lambda: NOW,
        ).ensure()


@pytest.mark.parametrize("public_name", ["https://panel.example", "bad name", "", "*.example.test"])
def test_tls_rejects_unbounded_public_names(tmp_path: Path, public_name: str) -> None:
    with pytest.raises(ValueError, match="public name"):
        TlsManager(tmp_path, public_names=(public_name,), clock=lambda: NOW)
