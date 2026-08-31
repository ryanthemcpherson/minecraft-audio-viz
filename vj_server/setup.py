"""One-time administrator setup state and failure-safe credential commit."""

from __future__ import annotations

import asyncio
import base64
import hmac
import json
import os
import re
import secrets
import tempfile
import threading
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from vj_server.auth import hash_password


class SetupError(RuntimeError):
    """Base error for managed administrator setup."""


class SetupStateError(SetupError):
    """Persistent setup or authentication state is invalid."""


class InvalidSetupToken(SetupError):
    """The supplied setup token is invalid, expired, exhausted, or consumed."""


class SetupValidationError(SetupError):
    """The proposed administrator credentials do not meet release policy."""


@dataclass(frozen=True, slots=True)
class SetupOffer:
    token: str = field(repr=False)
    expires_at: datetime


class SetupManager:
    TOKEN_LIFETIME = timedelta(minutes=30)
    MAX_ATTEMPTS = 5

    @staticmethod
    def validate_token(token: str) -> None:
        _validate_setup_token(token)

    def __init__(
        self,
        state_directory: Path,
        *,
        renderer_secret: bytes | str,
        clock: Callable[[], datetime] | None = None,
        transaction_hook: Callable[[str], None] | None = None,
    ) -> None:
        secret = (
            renderer_secret.encode("utf-8")
            if isinstance(renderer_secret, str)
            else bytes(renderer_secret)
        )
        if len(secret) < 32:
            raise ValueError("renderer secret must contain at least 32 bytes")
        self.state_directory = state_directory.absolute()
        self.state_path = self.state_directory / "setup.json"
        self.auth_path = self.state_directory / "auth.json"
        self.journal_path = self.state_directory / "setup-journal.json"
        self._renderer_secret = secret
        self._clock = clock or (lambda: datetime.now(UTC))
        self._transaction_hook = transaction_hook
        self._lock = threading.RLock()
        self._create_lock = asyncio.Lock()
        with self._lock:
            self._recover_journal()

    def rotate(self, token: str | None = None) -> SetupOffer:
        with self._lock:
            self._recover_journal()
            now = _utc(self._clock())
            expires_at = now + self.TOKEN_LIFETIME
            token = token or secrets.token_urlsafe(32)
            _validate_setup_token(token)
            state = {
                "version": 1,
                "token_hmac": self._token_hmac(token),
                "created_at": _format_time(now),
                "expires_at": _format_time(expires_at),
                "attempts": 0,
                "max_attempts": self.MAX_ATTEMPTS,
                "consumed": False,
            }
            _atomic_write_json(self.state_path, state, 0o600)
            return SetupOffer(token=token, expires_at=expires_at)

    def status(self) -> str:
        with self._lock:
            self._recover_journal()
            state = self._load_state()
            if state is None:
                return "expired"
            if state["consumed"]:
                return "complete"
            if not self._available(state):
                return "expired"
            return "available"

    def verify(self, token: str) -> bool:
        with self._lock:
            self._recover_journal()
            state = self._load_state()
            return self._verify_state(state, token, count_failure=True)

    async def status_async(self) -> str:
        return await asyncio.to_thread(self.status)

    async def verify_async(self, token: str) -> bool:
        return await asyncio.to_thread(self.verify, token)

    async def create_admin(self, token: str, username: str, password: str) -> str:
        async with self._create_lock:
            return await asyncio.to_thread(self._create_admin_sync, token, username, password)

    def _create_admin_sync(self, token: str, username: str, password: str) -> str:
        with self._lock:
            self._recover_journal()
            state = self._load_state()
            if not self._verify_state(state, token, count_failure=True):
                raise InvalidSetupToken("invalid or expired setup token")
            normalized_username = _normalize_username(username)
            _validate_password(password)
            password_hash = hash_password(password, "bcrypt")
            state = self._load_state()
            if not self._verify_state(state, token, count_failure=False):
                raise InvalidSetupToken("invalid or expired setup token")
            assert state is not None
            auth = self._load_auth()
            operators = auth.setdefault("vj_operators", {})
            operators[normalized_username] = {
                "name": normalized_username,
                "key_hash": password_hash,
            }
            consumed_state = dict(state)
            consumed_state["consumed"] = True
            journal = {
                "version": 1,
                "operation": "create_admin",
                "setup": consumed_state,
                "auth": auth,
            }
            _atomic_write_json(self.journal_path, journal, 0o600)
            self._boundary("journal_written")
            _atomic_write_json(self.state_path, consumed_state, 0o600)
            self._boundary("setup_consumed")
            _atomic_write_json(self.auth_path, auth, 0o600)
            self._boundary("auth_written")
            self.journal_path.unlink(missing_ok=True)
            _fsync_directory(self.state_directory)
            return normalized_username

    def validate_auth_file(self) -> None:
        with self._lock:
            self._load_auth()

    def validate_state_file(self) -> None:
        with self._lock:
            if self._load_state() is None:
                raise SetupStateError("setup state is missing")

    def _token_hmac(self, token: str) -> str:
        return hmac.digest(self._renderer_secret, token.encode("utf-8"), "sha256").hex()

    def _verify_state(
        self,
        state: dict[str, Any] | None,
        token: str,
        *,
        count_failure: bool,
    ) -> bool:
        if state is None or not isinstance(token, str) or not self._available(state):
            return False
        try:
            candidate = self._token_hmac(token)
        except UnicodeEncodeError:
            candidate = "0" * 64
        if hmac.compare_digest(candidate, state["token_hmac"]):
            return True
        if count_failure:
            failed_state = dict(state)
            failed_state["attempts"] += 1
            _atomic_write_json(self.state_path, failed_state, 0o600)
        return False

    def _available(self, state: dict[str, Any]) -> bool:
        return (
            not state["consumed"]
            and state["attempts"] < state["max_attempts"]
            and _utc(self._clock()) <= _parse_time(state["expires_at"])
        )

    def _load_state(self) -> dict[str, Any] | None:
        if not self.state_path.exists():
            return None
        return self._validate_state(_read_json_object(self.state_path))

    def _validate_state(self, state: dict[str, Any]) -> dict[str, Any]:
        try:
            if state.get("version") != 1:
                raise ValueError("unsupported version")
            token_hmac = state["token_hmac"]
            if not isinstance(token_hmac, str) or not re.fullmatch(r"[0-9a-f]{64}", token_hmac):
                raise ValueError("invalid token digest")
            attempts = state["attempts"]
            max_attempts = state["max_attempts"]
            consumed = state["consumed"]
            if (
                not isinstance(attempts, int)
                or isinstance(attempts, bool)
                or attempts < 0
                or not isinstance(max_attempts, int)
                or isinstance(max_attempts, bool)
                or max_attempts != self.MAX_ATTEMPTS
                or not isinstance(consumed, bool)
            ):
                raise ValueError("invalid counters")
            created_at = _parse_time(state["created_at"])
            expires_at = _parse_time(state["expires_at"])
            if expires_at <= created_at:
                raise ValueError("invalid expiry")
        except (KeyError, TypeError, ValueError) as error:
            raise SetupStateError("setup state is invalid") from error
        return state

    def _load_auth(self) -> dict[str, Any]:
        if not self.auth_path.is_file():
            raise SetupStateError("authentication state is missing")
        return self._validate_auth(_read_json_object(self.auth_path))

    def _validate_auth(self, auth: dict[str, Any]) -> dict[str, Any]:
        for section_name in ("djs", "vj_operators"):
            section = auth.get(section_name)
            if not isinstance(section, dict):
                raise SetupStateError("authentication state is invalid")
            for entry in section.values():
                if not isinstance(entry, dict) or not str(entry.get("key_hash", "")).startswith(
                    ("bcrypt:", "sha256:")
                ):
                    raise SetupStateError("authentication state contains an invalid credential")
        return auth

    def _recover_journal(self) -> None:
        if not self.journal_path.exists():
            return
        journal = _read_json_object(self.journal_path)
        if journal.get("version") != 1 or journal.get("operation") != "create_admin":
            raise SetupStateError("administrator setup journal is invalid")
        try:
            setup_state = self._validate_state(journal["setup"])
            auth = self._validate_auth(journal["auth"])
        except (AttributeError, KeyError, TypeError, SetupStateError) as error:
            raise SetupStateError("administrator setup journal is invalid") from error
        if not setup_state["consumed"]:
            raise SetupStateError("administrator setup journal is invalid")
        _atomic_write_json(self.state_path, setup_state, 0o600)
        _atomic_write_json(self.auth_path, auth, 0o600)
        self.journal_path.unlink(missing_ok=True)
        _fsync_directory(self.state_directory)

    def _boundary(self, name: str) -> None:
        if self._transaction_hook is not None:
            self._transaction_hook(name)


def _normalize_username(username: str) -> str:
    if not isinstance(username, str):
        raise SetupValidationError("username must be 3-32 ASCII characters")
    normalized = unicodedata.normalize("NFKC", username).lower()
    try:
        normalized.encode("ascii")
    except UnicodeEncodeError as error:
        raise SetupValidationError("username must be 3-32 ASCII characters") from error
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{2,31}", normalized):
        raise SetupValidationError("username must be 3-32 ASCII characters")
    return normalized


def _validate_setup_token(token: str) -> None:
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise ValueError("setup token must contain exactly 256 Base64URL bits")
    try:
        decoded = base64.urlsafe_b64decode(token + "=")
    except (ValueError, TypeError) as error:
        raise ValueError("setup token must contain exactly 256 Base64URL bits") from error
    if len(decoded) != 32:
        raise ValueError("setup token must contain exactly 256 Base64URL bits")


def _validate_password(password: str) -> None:
    if not isinstance(password, str):
        raise SetupValidationError("password must contain 12-72 UTF-8 bytes")
    try:
        password_bytes = password.encode("utf-8")
    except UnicodeEncodeError as error:
        raise SetupValidationError("password must contain valid UTF-8 characters") from error
    if not 12 <= len(password_bytes) <= 72 or any(
        character == "\x00" or unicodedata.category(character) == "Cc" for character in password
    ):
        raise SetupValidationError("password must contain 12-72 UTF-8 bytes without controls")


def _atomic_write_json(path: Path, value: dict[str, Any], mode: int) -> None:
    payload = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as destination:
            destination.write(payload)
            destination.flush()
            os.fsync(destination.fileno())
        os.chmod(temporary_path, mode)
        os.replace(temporary_path, path)
        _fsync_directory(path.parent)
    finally:
        temporary_path.unlink(missing_ok=True)


def _read_json_object(path: Path) -> dict[str, Any]:
    try:
        if path.stat().st_size > 256 * 1024:
            raise SetupStateError(f"persistent state is too large: {path.name}")
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SetupStateError(f"persistent state is invalid: {path.name}") from error
    if not isinstance(value, dict):
        raise SetupStateError(f"persistent state is invalid: {path.name}")
    return value


def _format_time(value: datetime) -> str:
    return _utc(value).isoformat().replace("+00:00", "Z")


def _parse_time(value: Any) -> datetime:
    if not isinstance(value, str) or len(value) > 64:
        raise ValueError("invalid timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return _utc(parsed)


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("setup clock must return a timezone-aware datetime")
    return value.astimezone(UTC)


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
