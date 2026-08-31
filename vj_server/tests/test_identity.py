from __future__ import annotations

import base64
import itertools
import os
from datetime import UTC, datetime
from pathlib import Path

import pytest

from vj_server.identity import IdentityStateError, IdentityStore

NOW = datetime(2026, 8, 31, 18, 0, tzinfo=UTC)
IDENTITY_FILES = ("tls.crt", "tls.key", "auth.json", "setup.json")


def setup_token(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def identity_store(
    root: Path,
    renderer_secret: bytes = b"r" * 32,
    token: str | None = None,
) -> IdentityStore:
    return IdentityStore(
        root,
        renderer_secret=renderer_secret,
        setup_token=token,
        clock=lambda: NOW,
    )


def test_empty_identity_is_created_once_without_plaintext_secrets(tmp_path: Path) -> None:
    first_token = setup_token(b"a" * 32)
    second_token = setup_token(b"b" * 32)
    store = identity_store(tmp_path, token=first_token)

    first = store.ensure()
    snapshot = {name: (tmp_path / name).read_bytes() for name in IDENTITY_FILES}
    second = identity_store(tmp_path, token=second_token).ensure()
    persistent = b"\n".join(snapshot.values())

    assert first.setup_offer is not None
    assert first.setup_offer.token == first_token
    assert second.setup_offer is not None
    assert second.setup_offer.token == second_token
    assert (tmp_path / "tls.crt").read_bytes() == snapshot["tls.crt"]
    assert (tmp_path / "tls.key").read_bytes() == snapshot["tls.key"]
    assert (tmp_path / "auth.json").read_bytes() == snapshot["auth.json"]
    assert (tmp_path / "setup.json").read_bytes() != snapshot["setup.json"]
    assert second.setup.verify(first_token) is False
    assert second.setup.verify(second_token) is True
    assert first.setup_offer.token.encode() not in persistent
    assert second.setup_offer.token.encode() not in (tmp_path / "setup.json").read_bytes()
    assert b"r" * 32 not in persistent
    assert not (tmp_path / "FIRST_LOGIN.txt").exists()
    if os.name != "nt":
        assert tmp_path.stat().st_mode & 0o777 == 0o700
        assert (tmp_path / "tls.key").stat().st_mode & 0o777 == 0o600
        assert (tmp_path / "auth.json").stat().st_mode & 0o777 == 0o600
        assert (tmp_path / "setup.json").stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize(
    "present_files",
    [
        selection
        for count in range(1, len(IDENTITY_FILES))
        for selection in itertools.combinations(IDENTITY_FILES, count)
    ],
)
def test_every_partial_identity_subset_is_refused_without_overwrite(
    tmp_path: Path,
    present_files: tuple[str, ...],
) -> None:
    for name in present_files:
        (tmp_path / name).write_text(f"keep-{name}", encoding="utf-8")
    before = {name: (tmp_path / name).read_bytes() for name in present_files}

    with pytest.raises(IdentityStateError, match="partial managed identity"):
        identity_store(tmp_path).ensure()

    assert {name: (tmp_path / name).read_bytes() for name in present_files} == before


def test_complete_but_invalid_identity_is_never_replaced(tmp_path: Path) -> None:
    identity_store(tmp_path).ensure()
    (tmp_path / "tls.crt").write_text("corrupt", encoding="utf-8")

    with pytest.raises(Exception, match="certificate"):
        identity_store(tmp_path).ensure()

    assert (tmp_path / "tls.crt").read_text(encoding="utf-8") == "corrupt"


def test_identity_rejects_short_renderer_secret(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="renderer secret"):
        identity_store(tmp_path, b"short")


def test_identity_rejects_invalid_parent_token_before_writing_state(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="256 Base64URL bits"):
        identity_store(tmp_path, token="invalid")

    assert list(tmp_path.iterdir()) == []
