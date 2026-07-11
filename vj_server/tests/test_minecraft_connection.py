"""Minecraft connection credential-wiring tests."""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import pytest
from websockets.exceptions import ConnectionClosedError
from websockets.frames import Close

import vj_server.viz_client as viz_client_module
from vj_server.vj_server import VJServer


class RejectingVizClient:
    instances: list["RejectingVizClient"] = []

    def __init__(self, host: str, port: int, **kwargs: Any) -> None:
        self.host = host
        self.port = port
        self.kwargs = kwargs
        self.connected = False
        self.disconnected = False
        self.__class__.instances.append(self)

    def on(self, _message_type: str, _callback: Any) -> None:
        return None

    async def connect(self) -> bool:
        return False

    async def disconnect(self) -> None:
        self.disconnected = True


class FailingDisconnectVizClient:
    def __init__(self, secret: str) -> None:
        self._secret = secret

    async def disconnect(self) -> None:
        raise ConnectionClosedError(Close(4001, f"peer echoed {self._secret}"), None, None)


class PostHandshakeSetupVizClient:
    instances: list["PostHandshakeSetupVizClient"] = []
    zone_result: list[dict[str, Any]] | BaseException = []
    registration_failure_index: int | None = None
    disconnect_error: BaseException | None = None

    def __init__(self, host: str, port: int, **kwargs: Any) -> None:
        self.host = host
        self.port = port
        self.kwargs = kwargs
        self.connected = False
        self.disconnected = False
        self.registration_count = 0
        self.__class__.instances.append(self)

    def on(self, _message_type: str, _callback: Any) -> None:
        self.registration_count += 1
        if self.registration_count == self.registration_failure_index:
            raise RuntimeError(f"handler registration {self.registration_count} failed")

    async def connect(self) -> bool:
        self.connected = True
        return True

    async def get_zones(self) -> list[dict[str, Any]]:
        if isinstance(self.zone_result, BaseException):
            raise self.zone_result
        return self.zone_result

    async def disconnect(self) -> None:
        self.connected = False
        self.disconnected = True
        if self.disconnect_error is not None:
            raise self.disconnect_error


@pytest.mark.asyncio
async def test_minecraft_secret_is_reused_for_each_relay_connection(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "relay-only-shared-secret"
    RejectingVizClient.instances.clear()
    monkeypatch.setattr(viz_client_module, "VizClient", RejectingVizClient)
    caplog.set_level(logging.DEBUG)
    server = VJServer(
        minecraft_host="mc.internal",
        minecraft_port=8765,
        minecraft_ws_secret=secret,
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )

    assert await server.connect_minecraft() is False
    assert await server.connect_minecraft() is False

    assert len(RejectingVizClient.instances) == 2
    assert all(instance.kwargs["auth_token"] == secret for instance in RejectingVizClient.instances)
    assert RejectingVizClient.instances[0].disconnected is True
    assert secret not in caplog.text
    assert secret not in json.dumps(server.get_health_stats())


@pytest.mark.asyncio
async def test_old_minecraft_client_disconnect_does_not_log_peer_close_reason(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "disconnect-shared-secret"
    RejectingVizClient.instances.clear()
    monkeypatch.setattr(viz_client_module, "VizClient", RejectingVizClient)
    server = VJServer(
        minecraft_ws_secret=secret,
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )
    server.viz_client = FailingDisconnectVizClient(secret)
    caplog.set_level(logging.DEBUG)

    assert await server.connect_minecraft() is False

    assert "ConnectionClosedError" in caplog.text
    assert secret not in caplog.text


@pytest.mark.parametrize(
    "zone_result",
    [
        pytest.param(asyncio.TimeoutError(), id="zone-query-timeout"),
        pytest.param([], id="no-zones"),
    ],
)
@pytest.mark.asyncio
async def test_post_handshake_setup_failure_disconnects_and_clears_candidate(
    monkeypatch: pytest.MonkeyPatch,
    zone_result: list[dict[str, Any]] | BaseException,
) -> None:
    PostHandshakeSetupVizClient.instances.clear()
    monkeypatch.setattr(PostHandshakeSetupVizClient, "zone_result", zone_result)
    monkeypatch.setattr(PostHandshakeSetupVizClient, "registration_failure_index", None)
    monkeypatch.setattr(PostHandshakeSetupVizClient, "disconnect_error", None)
    monkeypatch.setattr(viz_client_module, "VizClient", PostHandshakeSetupVizClient)
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )

    assert await server.connect_minecraft() is False

    candidate = PostHandshakeSetupVizClient.instances[0]
    assert candidate.disconnected is True
    assert candidate.connected is False
    assert server.viz_client is None


@pytest.mark.parametrize("registration_failure_index", [1, 2])
@pytest.mark.asyncio
async def test_handler_registration_failure_disconnects_and_clears_candidate(
    monkeypatch: pytest.MonkeyPatch,
    registration_failure_index: int,
) -> None:
    PostHandshakeSetupVizClient.instances.clear()
    monkeypatch.setattr(PostHandshakeSetupVizClient, "zone_result", [])
    monkeypatch.setattr(
        PostHandshakeSetupVizClient,
        "registration_failure_index",
        registration_failure_index,
    )
    monkeypatch.setattr(PostHandshakeSetupVizClient, "disconnect_error", None)
    monkeypatch.setattr(viz_client_module, "VizClient", PostHandshakeSetupVizClient)
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )

    with pytest.raises(RuntimeError, match="handler registration"):
        await server.connect_minecraft()

    candidate = PostHandshakeSetupVizClient.instances[0]
    assert candidate.disconnected is True
    assert server.viz_client is None


@pytest.mark.asyncio
async def test_cancelled_candidate_disconnect_still_clears_reference(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    PostHandshakeSetupVizClient.instances.clear()
    monkeypatch.setattr(PostHandshakeSetupVizClient, "zone_result", [])
    monkeypatch.setattr(PostHandshakeSetupVizClient, "registration_failure_index", None)
    monkeypatch.setattr(
        PostHandshakeSetupVizClient,
        "disconnect_error",
        asyncio.CancelledError(),
    )
    monkeypatch.setattr(viz_client_module, "VizClient", PostHandshakeSetupVizClient)
    server = VJServer(
        require_auth=False,
        show_spectrograph=False,
        metrics_port=None,
    )

    with pytest.raises(asyncio.CancelledError):
        await server.connect_minecraft()

    candidate = PostHandshakeSetupVizClient.instances[0]
    assert candidate.disconnected is True
    assert server.viz_client is None
