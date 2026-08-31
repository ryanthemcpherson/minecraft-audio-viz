from __future__ import annotations

import asyncio
import json
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

import vj_server.setup as setup_service_module
from vj_server.auth import verify_password
from vj_server.setup import (
    InvalidSetupToken,
    SetupManager,
    SetupStateError,
    SetupValidationError,
)

NOW = datetime(2026, 8, 31, 18, 0, tzinfo=UTC)


class MutableClock:
    def __init__(self, current: datetime = NOW) -> None:
        self.current = current

    def __call__(self) -> datetime:
        return self.current


def setup_manager(
    root: Path,
    *,
    clock: MutableClock | None = None,
    transaction_hook=None,
) -> SetupManager:
    root.mkdir(parents=True, exist_ok=True)
    (root / "auth.json").write_text(
        json.dumps({"djs": {}, "vj_operators": {}}) + "\n",
        encoding="utf-8",
    )
    return SetupManager(
        root,
        renderer_secret=b"r" * 32,
        clock=clock or MutableClock(),
        transaction_hook=transaction_hook,
    )


def test_setup_rotation_stores_only_hmac_and_invalidates_previous_offer(tmp_path: Path) -> None:
    manager = setup_manager(tmp_path)
    first = manager.rotate()
    state_text = manager.state_path.read_text(encoding="utf-8")
    second = manager.rotate()

    assert first.token not in state_text
    assert first.token not in repr(first)
    assert len(first.token) >= 43
    assert len(json.loads(state_text)["token_hmac"]) == 64
    assert manager.verify(first.token) is False
    assert manager.verify(second.token) is True
    assert manager.status() == "available"


def test_setup_rotation_accepts_exact_parent_generated_256_bit_token(tmp_path: Path) -> None:
    manager = setup_manager(tmp_path)
    token = "A" * 43

    offer = manager.rotate(token)

    assert offer.token == token
    assert manager.verify(token) is True


@pytest.mark.parametrize("token", ["short", "A" * 42, "A" * 44, "!" * 43])
def test_setup_rotation_rejects_invalid_parent_token(tmp_path: Path, token: str) -> None:
    manager = setup_manager(tmp_path)

    with pytest.raises(ValueError, match="256 Base64URL bits"):
        manager.rotate(token)


def test_setup_expires_and_locks_after_five_failures(tmp_path: Path) -> None:
    clock = MutableClock()
    manager = setup_manager(tmp_path, clock=clock)
    offer = manager.rotate()

    for _ in range(5):
        assert manager.verify("wrong-token") is False

    assert manager.verify(offer.token) is False
    assert manager.status() == "expired"

    replacement = manager.rotate()
    clock.current = replacement.expires_at + timedelta(microseconds=1)
    assert manager.verify(replacement.token) is False
    assert manager.status() == "expired"


def test_non_utf8_setup_token_is_a_counted_generic_failure(tmp_path: Path) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()

    assert manager.verify("\ud800") is False
    state = json.loads(manager.state_path.read_text(encoding="utf-8"))
    assert state["attempts"] == 1
    assert manager.verify(offer.token) is True


@pytest.mark.asyncio
async def test_successful_setup_consumes_token_and_stores_only_bcrypt(tmp_path: Path) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()

    username = await manager.create_admin(
        offer.token,
        "VJ_Admin",
        "correct horse battery",
    )
    stored_text = manager.auth_path.read_text(encoding="utf-8")
    stored = json.loads(stored_text)
    password_hash = stored["vj_operators"]["vj_admin"]["key_hash"]

    assert username == "vj_admin"
    assert password_hash.startswith("bcrypt:$2")
    assert password_hash.split("$")[2] == "12"
    assert verify_password("correct horse battery", password_hash)
    assert "correct horse battery" not in stored_text
    assert offer.token not in manager.state_path.read_text(encoding="utf-8")
    assert manager.verify(offer.token) is False
    assert manager.status() == "complete"


@pytest.mark.parametrize(
    "username",
    ["ab", "a" * 33, "name with spaces", "pånel", "_leading", "trailing!"],
)
@pytest.mark.asyncio
async def test_setup_rejects_invalid_normalized_usernames(tmp_path: Path, username: str) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()

    with pytest.raises(SetupValidationError, match="username"):
        await manager.create_admin(offer.token, username, "correct horse battery")

    assert manager.verify(offer.token) is True


@pytest.mark.parametrize(
    "password",
    ["short", "a" * 73, "valid-length\x00bad", "valid-length\nBad", "valid-length\ud800bad"],
)
@pytest.mark.asyncio
async def test_setup_rejects_invalid_password_bytes(tmp_path: Path, password: str) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()

    with pytest.raises(SetupValidationError, match="password"):
        await manager.create_admin(offer.token, "operator", password)

    assert manager.verify(offer.token) is True


@pytest.mark.asyncio
async def test_concurrent_admin_creation_consumes_offer_once(tmp_path: Path) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()

    outcomes = await asyncio.gather(
        manager.create_admin(offer.token, "operator_one", "correct horse battery"),
        manager.create_admin(offer.token, "operator_two", "correct horse battery"),
        return_exceptions=True,
    )

    assert sum(isinstance(outcome, str) for outcome in outcomes) == 1
    assert sum(isinstance(outcome, InvalidSetupToken) for outcome in outcomes) == 1
    stored = json.loads(manager.auth_path.read_text(encoding="utf-8"))
    assert len(stored["vj_operators"]) == 1


@pytest.mark.asyncio
async def test_password_hashing_does_not_block_ingress_event_loop(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()
    event_loop_progressed = False

    def slow_hash(_password: str, _method: str) -> str:
        time.sleep(0.1)
        return "bcrypt:$2b$12$test-only-hash"

    async def mark_progress() -> None:
        nonlocal event_loop_progressed
        await asyncio.sleep(0.01)
        event_loop_progressed = True

    monkeypatch.setattr(setup_service_module, "hash_password", slow_hash)
    await asyncio.gather(
        manager.create_admin(offer.token, "operator", "correct horse battery"),
        mark_progress(),
    )

    assert event_loop_progressed is True


@pytest.mark.asyncio
async def test_failed_token_persistence_does_not_block_ingress_event_loop(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manager = setup_manager(tmp_path)
    manager.rotate()
    event_loop_progressed = False
    atomic_write = setup_service_module._atomic_write_json

    def slow_write(*args, **kwargs) -> None:
        time.sleep(0.1)
        atomic_write(*args, **kwargs)

    async def mark_progress() -> None:
        nonlocal event_loop_progressed
        await asyncio.sleep(0.01)
        event_loop_progressed = True

    monkeypatch.setattr(setup_service_module, "_atomic_write_json", slow_write)
    result, _ = await asyncio.gather(manager.verify_async("wrong-token"), mark_progress())

    assert result is False
    assert event_loop_progressed is True


@pytest.mark.asyncio
async def test_admin_transaction_fsync_does_not_block_ingress_event_loop(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manager = setup_manager(tmp_path)
    offer = manager.rotate()
    event_loop_progressed = False
    atomic_write = setup_service_module._atomic_write_json

    def slow_write(*args, **kwargs) -> None:
        time.sleep(0.1)
        atomic_write(*args, **kwargs)

    def fast_hash(_password: str, _method: str) -> str:
        return "bcrypt:$2b$12$test-only-hash"

    async def mark_progress() -> None:
        nonlocal event_loop_progressed
        await asyncio.sleep(0.01)
        event_loop_progressed = True

    monkeypatch.setattr(setup_service_module, "_atomic_write_json", slow_write)
    monkeypatch.setattr(setup_service_module, "hash_password", fast_hash)
    await asyncio.gather(
        manager.create_admin(offer.token, "operator", "correct horse battery"),
        mark_progress(),
    )

    assert event_loop_progressed is True


@pytest.mark.parametrize("failure_boundary", ["journal_written", "setup_consumed", "auth_written"])
@pytest.mark.asyncio
async def test_setup_transaction_recovers_every_atomic_boundary(
    tmp_path: Path,
    failure_boundary: str,
) -> None:
    failed = False

    def terminate(boundary: str) -> None:
        nonlocal failed
        if boundary == failure_boundary and not failed:
            failed = True
            raise RuntimeError("simulated termination")

    manager = setup_manager(tmp_path, transaction_hook=terminate)
    offer = manager.rotate()

    with pytest.raises(RuntimeError, match="simulated termination"):
        await manager.create_admin(offer.token, "operator", "correct horse battery")

    recovered = SetupManager(
        tmp_path,
        renderer_secret=b"r" * 32,
        clock=MutableClock(),
    )
    stored_text = recovered.auth_path.read_text(encoding="utf-8")
    stored = json.loads(stored_text)
    all_persistent_text = "\n".join(
        path.read_text(encoding="utf-8") for path in tmp_path.iterdir() if path.is_file()
    )

    assert "operator" in stored["vj_operators"]
    assert recovered.verify(offer.token) is False
    assert not recovered.journal_path.exists()
    assert "correct horse battery" not in all_persistent_text


def test_invalid_journal_is_rejected_without_overwriting_auth(tmp_path: Path) -> None:
    manager = setup_manager(tmp_path)
    manager.rotate()
    original_auth = manager.auth_path.read_bytes()
    consumed_setup = json.loads(manager.state_path.read_text(encoding="utf-8"))
    consumed_setup["consumed"] = True
    manager.journal_path.write_text(
        json.dumps(
            {
                "version": 1,
                "operation": "create_admin",
                "setup": consumed_setup,
                "auth": {
                    "djs": {},
                    "vj_operators": {
                        "operator": {
                            "name": "operator",
                            "key_hash": "plaintext-password",
                        }
                    },
                },
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(SetupStateError, match="journal is invalid"):
        SetupManager(
            tmp_path,
            renderer_secret=b"r" * 32,
            clock=MutableClock(),
        )

    assert manager.auth_path.read_bytes() == original_auth
    assert manager.journal_path.exists()
