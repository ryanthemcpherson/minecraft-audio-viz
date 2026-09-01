"""DJ invite metadata must stay behind the authenticated administrator boundary."""

from __future__ import annotations

import msgspec.json as mjson
import pytest

from vj_server.auth import hash_password
from vj_server.models import ConnectCode, DJAuthConfig, _json_str
from vj_server.vj_server import VJServer

ADMIN_PASSWORD = "administrator-only-password"
RENDERER_SECRET = "renderer-secret-that-must-never-leave-loopback"


class ScriptedAdminWebSocket:
    def __init__(self, commands: list[dict]) -> None:
        self._auth_received = False
        self._commands = iter(_json_str(command) for command in commands)
        self.remote_address = ("127.0.0.1", 12345)
        self.sent: list[str] = []
        self.close_code: int | None = None

    async def recv(self) -> str:
        if self._auth_received:
            raise RuntimeError("authentication requested more than once")
        self._auth_received = True
        return _json_str(
            {
                "type": "vj_auth",
                "username": "operator",
                "password": ADMIN_PASSWORD,
            }
        )

    def __aiter__(self) -> ScriptedAdminWebSocket:
        return self

    async def __anext__(self) -> str:
        try:
            return next(self._commands)
        except StopIteration as error:
            raise StopAsyncIteration from error

    async def send(self, message: str) -> None:
        self.sent.append(message)

    async def close(self, code: int = 1000, _reason: str = "") -> None:
        self.close_code = code


def server_for_invites(*, public_url: str | None, fingerprint: str | None) -> VJServer:
    return VJServer(
        require_auth=True,
        auth_config=DJAuthConfig(
            vj_operators={
                "operator": {"key_hash": hash_password(ADMIN_PASSWORD)},
            }
        ),
        minecraft_ws_secret=RENDERER_SECRET,
        public_url=public_url,
        certificate_fingerprint=fingerprint,
        show_spectrograph=False,
        metrics_port=None,
    )


@pytest.mark.parametrize(
    ("public_url", "fingerprint", "expected_runtime"),
    [
        (None, None, {"public_url": None}),
        (
            "https://panel.example.test:8443/",
            None,
            {"public_url": "https://panel.example.test:8443"},
        ),
        (
            "https://panel.example.test:8443/",
            "ab" * 32,
            {
                "public_url": "https://panel.example.test:8443",
                "certificate_sha256": "AB" * 32,
            },
        ),
    ],
)
@pytest.mark.asyncio
async def test_generated_code_returns_only_invite_runtime_metadata_after_admin_auth(
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
    public_url: str | None,
    fingerprint: str | None,
    expected_runtime: dict,
) -> None:
    expires_at = 1_788_200_000
    monkeypatch.setattr(
        ConnectCode,
        "generate",
        staticmethod(
            lambda _ttl: ConnectCode(
                code="BEAT-7K3M",
                created_at=expires_at - 1800,
                expires_at=expires_at,
            )
        ),
    )
    server = server_for_invites(public_url=public_url, fingerprint=fingerprint)
    websocket = ScriptedAdminWebSocket([{"type": "generate_connect_code", "ttl_minutes": 30}])

    await server._handle_browser_client(websocket)

    messages = [mjson.decode(message) for message in websocket.sent]
    generated = next(message for message in messages if message["type"] == "connect_code_generated")
    assert generated == {
        "type": "connect_code_generated",
        "code": "BEAT-7K3M",
        "expires_at": expires_at,
        "ttl_minutes": 30,
        "runtime": expected_runtime,
    }
    serialized = _json_str(generated)
    assert ADMIN_PASSWORD not in serialized
    assert RENDERER_SECRET not in serialized
    assert "key_hash" not in serialized
    assert "BEAT-7K3M" not in caplog.text
    assert "mcav://connect" not in caplog.text


@pytest.mark.parametrize(
    "public_url",
    [
        "http://panel.example.test/",
        "https://admin:secret@panel.example.test/",
        "https://panel.example.test/control",
        "https://panel.example.test/?secret=value",
        "https://panel.example.test/#secret",
    ],
)
def test_server_rejects_noncanonical_public_invite_url(public_url: str) -> None:
    with pytest.raises(ValueError, match="public URL"):
        server_for_invites(public_url=public_url, fingerprint=None)


@pytest.mark.parametrize("ttl_minutes", [0, 1441, "30", True])
@pytest.mark.asyncio
async def test_connect_code_ttl_is_bounded_before_generation(ttl_minutes: object) -> None:
    server = server_for_invites(
        public_url="https://panel.example.test/",
        fingerprint=None,
    )
    websocket = ScriptedAdminWebSocket(
        [{"type": "generate_connect_code", "ttl_minutes": ttl_minutes}]
    )

    await server._handle_browser_client(websocket)

    messages = [mjson.decode(message) for message in websocket.sent]
    assert {message["type"] for message in messages} == {"auth_success", "vj_state", "error"}
    assert next(message for message in messages if message["type"] == "error") == {
        "type": "error",
        "message": "Connect code lifetime must be between 1 and 1440 minutes",
    }
    assert server._connect_codes == {}


@pytest.mark.asyncio
async def test_preview_route_rejects_all_connect_code_commands() -> None:
    server = server_for_invites(
        public_url="https://panel.example.test/",
        fingerprint="AB" * 32,
    )
    websocket = ScriptedAdminWebSocket(
        [
            {"type": "generate_connect_code", "ttl_minutes": 30},
            {"type": "get_connect_codes"},
            {"type": "revoke_connect_code", "code": "BEAT-7K3M"},
        ]
    )

    await server._handle_preview_client(websocket)

    messages = [mjson.decode(message) for message in websocket.sent]
    assert [message["type"] for message in messages] == [
        "auth_success",
        "vj_state",
        "error",
        "error",
        "error",
    ]
    assert all(
        message["message"] == "Command is only available on the administrator route"
        for message in messages
        if message["type"] == "error"
    )
    assert server._connect_codes == {}
    assert all("BEAT-7K3M" not in _json_str(message) for message in messages)


@pytest.mark.asyncio
async def test_connect_code_broadcast_only_targets_admin_clients() -> None:
    server = server_for_invites(
        public_url="https://panel.example.test/",
        fingerprint=None,
    )
    admin = ScriptedAdminWebSocket([])
    preview = ScriptedAdminWebSocket([])
    server._broadcast_clients.update({admin, preview})
    server._admin_clients.add(admin)
    server._connect_codes["BEAT-7K3M"] = ConnectCode(
        code="BEAT-7K3M",
        created_at=1,
        expires_at=4_102_444_800,
    )

    await server._broadcast_connect_codes()

    assert [mjson.decode(message)["type"] for message in admin.sent] == ["connect_codes"]
    assert preview.sent == []
