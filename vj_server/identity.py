"""Persistent managed-runtime identity orchestration."""

from __future__ import annotations

import json
import os
import tempfile
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from vj_server.setup import SetupManager, SetupOffer
from vj_server.tls import TlsIdentity, TlsManager


class IdentityStateError(RuntimeError):
    """The managed identity is partial or otherwise unsafe to replace."""


@dataclass(frozen=True, slots=True)
class IdentityState:
    tls: TlsIdentity
    setup: SetupManager
    setup_offer: SetupOffer | None
    auth_path: Path


class IdentityStore:
    """Create an empty managed identity once and validate it thereafter."""

    def __init__(
        self,
        state_directory: Path,
        *,
        renderer_secret: bytes | str,
        public_names: Iterable[str] = (),
        supplied_certificate: Path | None = None,
        supplied_private_key: Path | None = None,
        setup_token: str | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        secret = (
            renderer_secret.encode("utf-8")
            if isinstance(renderer_secret, str)
            else bytes(renderer_secret)
        )
        if len(secret) < 32:
            raise ValueError("renderer secret must contain at least 32 bytes")
        if setup_token is not None:
            SetupManager.validate_token(setup_token)
        self.state_directory = state_directory.absolute()
        self.auth_path = self.state_directory / "auth.json"
        self.setup_path = self.state_directory / "setup.json"
        self._renderer_secret = secret
        self._setup_token = setup_token
        self._clock = clock
        self._tls = TlsManager(
            self.state_directory,
            public_names=public_names,
            supplied_certificate=supplied_certificate,
            supplied_private_key=supplied_private_key,
            clock=clock,
        )
        self.setup: SetupManager | None = None

    def ensure(self) -> IdentityState:
        self.state_directory.mkdir(parents=True, exist_ok=True)
        _set_mode(self.state_directory, 0o700)
        generated_tls_paths = (
            (self._tls.certificate_path, self._tls.private_key_path) if self._tls.generated else ()
        )
        identity_paths = (*generated_tls_paths, self.auth_path, self.setup_path)
        existing_count = sum(path.exists() for path in identity_paths)
        if existing_count not in (0, len(identity_paths)):
            raise IdentityStateError(
                "partial managed identity; recovery must not overwrite persistent credentials"
            )

        first_run = existing_count == 0
        tls_identity = self._tls.ensure()
        if first_run:
            _atomic_write_json(self.auth_path, {"djs": {}, "vj_operators": {}}, 0o600)

        setup = SetupManager(
            self.state_directory,
            renderer_secret=self._renderer_secret,
            clock=self._clock,
        )
        setup_offer = setup.rotate(self._setup_token) if setup.status() != "complete" else None
        setup.validate_auth_file()
        setup.validate_state_file()
        _set_mode(self.auth_path, 0o600)
        _set_mode(self.setup_path, 0o600)
        self.setup = setup
        return IdentityState(
            tls=tls_identity,
            setup=setup,
            setup_offer=setup_offer,
            auth_path=self.auth_path,
        )


def _atomic_write_json(path: Path, value: dict, mode: int) -> None:
    content = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as destination:
            destination.write(content)
            destination.flush()
            os.fsync(destination.fileno())
        os.chmod(temporary_path, mode)
        os.replace(temporary_path, path)
        _fsync_directory(path.parent)
    finally:
        temporary_path.unlink(missing_ok=True)


def _set_mode(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError as error:
        if os.name != "nt":
            raise IdentityStateError(
                f"could not secure managed identity permissions: {error}"
            ) from error


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
