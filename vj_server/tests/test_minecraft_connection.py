"""Minecraft connection credential-wiring tests."""

from __future__ import annotations

import json
import logging
from typing import Any

import pytest

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
