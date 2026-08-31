from __future__ import annotations

import json
import re
from base64 import b64encode
from datetime import datetime
from urllib.parse import urlsplit

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

TOP_LEVEL_FIELDS = frozenset(
    {
        "schema_version",
        "generation",
        "release_version",
        "runtime_api_min",
        "runtime_api_max",
        "published_at",
        "expires_at",
        "signing_key_id",
        "artifacts",
    }
)
ARTIFACT_FIELDS = frozenset(
    {
        "url",
        "archive_size",
        "uncompressed_size",
        "sha256",
        "entrypoint",
        "files_manifest_sha256",
    }
)
PLATFORMS = frozenset({"linux-x86_64", "linux-aarch64", "windows-x86_64"})
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
KEY_ID_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$")
DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ENTRYPOINT_PATTERN = re.compile(r"^[0-9A-Za-z._-]+(?:/[0-9A-Za-z._-]+)*$")


class ManifestBuildError(ValueError):
    """Raised when release metadata cannot be represented by manifest schema v1."""


def canonical_manifest_bytes(document: dict[str, object]) -> bytes:
    """Validate and encode one runtime manifest using the v1 canonical byte form."""

    _validate_manifest(document)
    try:
        encoded = json.dumps(
            document,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, UnicodeEncodeError, ValueError) as error:
        raise ManifestBuildError("manifest is not canonical UTF-8 JSON") from error
    return encoded + b"\n"


def sign_manifest(
    document: dict[str, object],
    private_key: Ed25519PrivateKey,
    key_id: str,
) -> tuple[bytes, bytes]:
    """Return canonical manifest and detached signature-envelope bytes."""

    if not isinstance(private_key, Ed25519PrivateKey):
        raise ManifestBuildError("private key must be Ed25519")
    payload = canonical_manifest_bytes(document)
    manifest_key_id = document["signing_key_id"]
    if key_id != manifest_key_id:
        raise ManifestBuildError("key ID must equal manifest signing_key_id")
    envelope = {
        "algorithm": "Ed25519",
        "key_id": key_id,
        "schema_version": 1,
        "signature": b64encode(private_key.sign(payload)).decode("ascii"),
    }
    envelope_bytes = (
        json.dumps(
            envelope,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        + b"\n"
    )
    return payload, envelope_bytes


def _validate_manifest(document: dict[str, object]) -> None:
    if not isinstance(document, dict):
        raise ManifestBuildError("manifest must be an object")
    _require_exact_fields(document, TOP_LEVEL_FIELDS, "manifest")

    _require_integer(document, "schema_version", minimum=1, maximum=1)
    _require_integer(document, "generation", minimum=1, maximum=2**63 - 1)
    runtime_api_min = _require_integer(document, "runtime_api_min", minimum=1, maximum=65535)
    runtime_api_max = _require_integer(document, "runtime_api_max", minimum=1, maximum=65535)
    if runtime_api_min > runtime_api_max:
        raise ManifestBuildError("runtime_api_min must not exceed runtime_api_max")

    release_version = _require_string(document, "release_version", maximum=64)
    if not VERSION_PATTERN.fullmatch(release_version):
        raise ManifestBuildError("release_version must be a semantic product version")

    key_id = _require_string(document, "signing_key_id", maximum=64)
    if not KEY_ID_PATTERN.fullmatch(key_id):
        raise ManifestBuildError("signing_key_id has invalid characters")

    published_at = _require_timestamp(document, "published_at")
    expires_at = _require_timestamp(document, "expires_at")
    if expires_at <= published_at:
        raise ManifestBuildError("expires_at must be after published_at")

    artifacts = document["artifacts"]
    if not isinstance(artifacts, dict):
        raise ManifestBuildError("artifacts must be an object")
    _require_exact_fields(artifacts, PLATFORMS, "artifacts")
    for platform in sorted(PLATFORMS):
        artifact_value = artifacts[platform]
        if not isinstance(artifact_value, dict):
            raise ManifestBuildError(f"artifact {platform} must be an object")
        _validate_artifact(platform, artifact_value)


def _validate_artifact(platform: str, artifact: dict[str, object]) -> None:
    _require_exact_fields(artifact, ARTIFACT_FIELDS, f"artifact {platform}")
    _require_integer(artifact, "archive_size", minimum=1, maximum=1024**3)
    _require_integer(artifact, "uncompressed_size", minimum=1, maximum=2 * 1024**3)

    for field in ("sha256", "files_manifest_sha256"):
        digest = _require_string(artifact, field, maximum=64)
        if not DIGEST_PATTERN.fullmatch(digest):
            raise ManifestBuildError(f"artifact {platform} {field} must be lowercase SHA-256")

    entrypoint = _require_string(artifact, "entrypoint", maximum=128)
    if not ENTRYPOINT_PATTERN.fullmatch(entrypoint):
        raise ManifestBuildError(f"artifact {platform} entrypoint is not a normalized path")

    url = _require_string(artifact, "url", maximum=2048)
    parsed = urlsplit(url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ManifestBuildError(f"artifact {platform} URL must be credential-free HTTPS")


def _require_exact_fields(value: dict[str, object], expected: frozenset[str], label: str) -> None:
    actual = set(value)
    unknown = sorted(actual - expected)
    if unknown:
        if label == "manifest":
            raise ManifestBuildError(f"unknown manifest field: {unknown[0]}")
        raise ManifestBuildError(f"unknown {label} field: {unknown[0]}")
    missing = sorted(expected - actual)
    if missing:
        raise ManifestBuildError(f"missing {label} field: {missing[0]}")


def _require_integer(value: dict[str, object], field: str, *, minimum: int, maximum: int) -> int:
    candidate = value[field]
    if isinstance(candidate, bool) or not isinstance(candidate, int):
        raise ManifestBuildError(f"{field} must be an integer")
    if not minimum <= candidate <= maximum:
        raise ManifestBuildError(f"{field} is outside the supported range")
    return candidate


def _require_string(value: dict[str, object], field: str, *, maximum: int) -> str:
    candidate = value[field]
    if not isinstance(candidate, str) or not candidate or len(candidate) > maximum:
        raise ManifestBuildError(f"{field} must be a non-empty bounded string")
    try:
        candidate.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ManifestBuildError(f"{field} must contain valid Unicode") from error
    return candidate


def _require_timestamp(value: dict[str, object], field: str) -> datetime:
    timestamp = _require_string(value, field, maximum=32)
    try:
        return datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ManifestBuildError(f"{field} must be UTC RFC 3339 seconds") from error
