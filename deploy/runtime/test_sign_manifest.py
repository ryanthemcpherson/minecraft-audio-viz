from __future__ import annotations

import json
from base64 import b64decode
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_der_private_key, load_der_public_key

from deploy.runtime.sign_manifest import (
    ManifestBuildError,
    canonical_manifest_bytes,
    sign_manifest,
)

FIXTURES = Path(__file__).parents[2] / "protocol" / "fixtures" / "runtime-release"


def valid_document() -> dict[str, object]:
    return {
        "schema_version": 1,
        "generation": 1,
        "release_version": "1.2.0",
        "runtime_api_min": 1,
        "runtime_api_max": 1,
        "published_at": "2026-08-31T00:00:00Z",
        "expires_at": "2026-09-30T00:00:00Z",
        "signing_key_id": "test-only-2026",
        "artifacts": {
            "linux-x86_64": artifact("linux-x86_64", "bin/audioviz-vj", "a", "b"),
            "linux-aarch64": artifact("linux-aarch64", "bin/audioviz-vj", "c", "d"),
            "windows-x86_64": artifact("windows-x86_64", "bin/audioviz-vj.exe", "e", "f"),
        },
    }


def artifact(platform: str, entrypoint: str, archive_hex: str, files_hex: str) -> dict[str, object]:
    return {
        "url": f"https://releases.mcav.live/runtime/1.2.0/{platform}.zip",
        "archive_size": 4096,
        "uncompressed_size": 8192,
        "sha256": archive_hex * 64,
        "entrypoint": entrypoint,
        "files_manifest_sha256": files_hex * 64,
    }


def test_canonical_manifest_matches_reviewed_golden_bytes() -> None:
    document = valid_document()

    assert canonical_manifest_bytes(document) == (FIXTURES / "valid-manifest.json").read_bytes()


def test_canonical_manifest_rejects_float() -> None:
    document = valid_document()
    document["generation"] = 1.5

    with pytest.raises(ManifestBuildError, match="generation must be an integer"):
        canonical_manifest_bytes(document)


def test_canonical_manifest_rejects_unknown_top_level_member() -> None:
    document = valid_document()
    document["surprise"] = True

    with pytest.raises(ManifestBuildError, match="unknown manifest field: surprise"):
        canonical_manifest_bytes(document)


def test_canonical_manifest_rejects_entrypoint_over_plugin_limit() -> None:
    document = valid_document()
    artifacts = document["artifacts"]
    assert isinstance(artifacts, dict)
    linux_artifact = artifacts["linux-x86_64"]
    assert isinstance(linux_artifact, dict)
    linux_artifact["entrypoint"] = "a" * 129

    with pytest.raises(ManifestBuildError, match="entrypoint must be a non-empty bounded string"):
        canonical_manifest_bytes(document)


def test_signature_matches_golden_and_verifies_exact_payload() -> None:
    document = valid_document()
    private_key = load_der_private_key(
        b64decode((FIXTURES / "test-private-key.pk8.b64").read_text(encoding="ascii")),
        password=None,
    )

    payload, envelope_bytes = sign_manifest(document, private_key, "test-only-2026")

    assert payload == (FIXTURES / "valid-manifest.json").read_bytes()
    assert envelope_bytes == (FIXTURES / "valid-manifest.sig.json").read_bytes()
    envelope = json.loads(envelope_bytes)
    public_key = load_der_public_key(
        b64decode((FIXTURES / "test-public-key.der.b64").read_text(encoding="ascii"))
    )
    assert isinstance(public_key, Ed25519PublicKey)
    public_key.verify(b64decode(envelope["signature"]), payload)


def test_sign_manifest_rejects_mismatched_key_id() -> None:
    document = valid_document()
    private_key = load_der_private_key(
        b64decode((FIXTURES / "test-private-key.pk8.b64").read_text(encoding="ascii")),
        password=None,
    )

    with pytest.raises(ManifestBuildError, match="key ID must equal manifest signing_key_id"):
        sign_manifest(document, private_key, "different-test-key")
