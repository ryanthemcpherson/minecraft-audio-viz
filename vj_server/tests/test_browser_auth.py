"""Focused browser authentication boundary and real gateway tests."""

import asyncio
import ssl
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path

import msgspec.json as mjson
import pytest
from aiohttp import ClientSession, TCPConnector, WSMsgType
from aiohttp.client_exceptions import WSServerHandshakeError

from vj_server.auth import hash_password
from vj_server.models import DJAuthConfig, _json_str
from vj_server.relay import RelayMixin
from vj_server.vj_server import VJServer
from vj_server.web_gateway import UnifiedWebConfig, start_unified_web_gateway

PUBLIC_ORIGIN = "https://203.0.113.9:18080"
TEST_TLS_KEY_BODY = """MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDOh/uZFLsVgV5s
iIdjALqIdVn349PPfGpiaysX7N/8mQoE1jCaXAqy4mLJ19v+mu1p+o8n3jrvFmNf
IkiQwI7WFL/NAHTKANNbQYtnb4HhfrVPISUa+OA+WbJYQdMM2zowJ6bUe7MSQDFg
JwZ53gxCUTd3975GDaPks4JSqQRvBd12xzXbDUl2TfreBIa+/nv5NwyBkuvzP+ba
FvZFpmqXPqnN3tC/wdJNii5IVIheLjrsoznziHUPMvmj3vnUDG4vJDwUsJA0Ilz3
zs5CwGEJEmVG3hXtreRSjXg3/gbPajGaZ3weBTbH+Aqc4Y6fLMqRFoGnAZ7Zicxp
PGVqtXnVAgMBAAECggEAGq8oGz259EvcOtKjB3AbKbFb1/LoNXkiN6gYD8XLpNPT
Hw/bhL4apcUpNWH96xXyUcyNPX3xiF2QpkSEMqumaNOSenayID1eEX7U957JHazk
2R3zsNnyAyxMpimPDqyuhnVBEVgQKW4A0ycHp6xAUjszGv136I4vnEdzMaHj0EIq
u08nHsApcNhPyXbeFjUsYSMxABeutkrG2C1byctWuwFv0I+BDmilANqH7ROMTnIN
XUTjDdemp0oA5UKUjDwMM+GnfoOFoXDvr14/vw/Yn/Ilg92WriahwwFCGKdfB3yN
X0dBAvAc74AUrWQGV98XGjbOxmq5tc+hjZd0bKihawKBgQDZ2TYvSdXP6CuiItYi
AKF2K7UQj7s4l+DQR3zfd+/16totkRjeB8YlwvCzMf+lRiIVlTAPidkfV680Z6+t
Bl+EzYOKT6tEVJ99vY2nWGVORly8jwJSdLxudHNi9WBkzmC8IIYPSh73wLvNVjXG
aFSYxUnx8LDJT9URdKWxtAbtYwKBgQDys2Ie9ApoCOs6N61ThgQPB5VrgwwdqGQW
cCE/cpIdWXFLZUqQDhyOKDRTuGXOXoaBbB8u3YRy+CYOkq7MpnmjUWWTulFQQg3w
63+3XEl07yjNtUz0mB6OUngTPLyCs6ron+FD4tiRydDkDwFpIkH3YFKV7vUtnxIQ
fmBfVEVdZwKBgAwh/tSPZisYISX8jrSCGHv+Xy029BRo0QqIkLnZcjHeDJyxEhN7
l1uPCdzREg3gZBGTp4OWB9OpDIb8p1oZmsRIteTEHyPFGsTkA7moQKwlWxDdXiG9
gqkcLzj5tY6nt9eCcDT1yde+kjcTcBdGxD9l7YJeB1qO6az5NCk7f49FAoGAbDWW
tf5Q9Xmkh1xzpx3FiX30HO9c44xEs8xixosqonNSlC8hQ4FHMgqy6fD5Uz3J8sJm
VNrnRutk7HJyBUTkTvDnvSoBPyt0U8psMzCuf7hyFOWU3ilE1mfmqY0W759zwCwo
n7/wl2/H9ybJljpz9vu3VqooHqMhxsDR7y7/jlkCgYBIVBTjfG5XfQF7g1SAMfuW
qPhRwWEQDO7qOKo5Ulb2hPK/i3K+QOZqGvbgFIQ7Z4Z7sPAxuWnB9BT3hkHhLbJP
bNpzFWEdruxBrnZWCT71d6XYOEfB/pugkSR8jJ0m0dIfaAq1IYEpCrS23xDvCoKO
I+kpeiMpu4cPlEY+XCBmvg=="""
TEST_TLS_CERT = """-----BEGIN CERTIFICATE-----
MIIDCTCCAfGgAwIBAgIUUV3Ka9zTBEuWk7kFEoYUhdAIHEYwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDgyMTE2MDQyNFoXDTM2MDgx
ODE2MDQyNFowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAzof7mRS7FYFebIiHYwC6iHVZ9+PTz3xqYmsrF+zf/JkK
BNYwmlwKsuJiydfb/prtafqPJ9467xZjXyJIkMCO1hS/zQB0ygDTW0GLZ2+B4X61
TyElGvjgPlmyWEHTDNs6MCem1HuzEkAxYCcGed4MQlE3d/e+Rg2j5LOCUqkEbwXd
dsc12w1Jdk363gSGvv57+TcMgZLr8z/m2hb2RaZqlz6pzd7Qv8HSTYouSFSIXi46
7KM584h1DzL5o9751AxuLyQ8FLCQNCJc987OQsBhCRJlRt4V7a3kUo14N/4Gz2ox
mmd8HgU2x/gKnOGOnyzKkRaBpwGe2YnMaTxlarV51QIDAQABo1MwUTAdBgNVHQ4E
FgQU6K63knyaUPUqj6ONQMEX8IA2TgEwHwYDVR0jBBgwFoAU6K63knyaUPUqj6ON
QMEX8IA2TgEwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAelSk
PNkcgBHTMatnIgUiHCk+ffDSJy3NvZYrmuUwfnc7fj1nVNgELemRX0cz/8bT2wDT
/s0U5Xw70tw8Twly1F4xi4+5/5YebaM3Himer7tst0bxFadIsVg5BIPVACM9+F2+
53jA3ryQFTW6K89Nkjlz4PwITJ46LuuVoa28V6zo5YCRpifGbxdBVOPsv0cW8rGy
x4RT0U4GBZwwuF784kgCgJpdGtEDxN9dt6vzmD9N1e++8DvTh57+jAv0Btwlozgk
SlaLZCMR1tuOqcufv+Idv7vsxUr0Cu5PNbr+CtH7GV+C+5SZmg3dLEX92ALlkjRr
WfAdyX7ANnJz4xm0ag==
-----END CERTIFICATE-----
"""


@dataclass
class LiveGateway:
    server: VJServer
    client: ClientSession
    websocket_url: str


@pytest.fixture
async def live_gateway(tmp_path: Path) -> AsyncIterator[LiveGateway]:
    cert_file = tmp_path / "tls.crt"
    key_file = tmp_path / "tls.key"
    private_key_label = "PRIVATE KEY"
    cert_file.write_text(TEST_TLS_CERT, encoding="ascii")
    key_file.write_text(
        f"-----BEGIN {private_key_label}-----\n"
        f"{TEST_TLS_KEY_BODY}\n"
        f"-----END {private_key_label}-----\n",
        encoding="ascii",
    )
    (tmp_path / "admin_panel").mkdir()
    (tmp_path / "preview_tool" / "frontend").mkdir(parents=True)
    server = VJServer(
        project_root=tmp_path,
        tls_cert=cert_file,
        tls_key=key_file,
        auth_config=DJAuthConfig(
            vj_operators={
                "lighting": {"key_hash": hash_password("lighting-secret")},
            }
        ),
        metrics_port=None,
        show_spectrograph=False,
    )
    runner = await start_unified_web_gateway(
        server._handle_browser_client,
        "127.0.0.1",
        0,
        server.server_ssl_context,
        UnifiedWebConfig(tmp_path, PUBLIC_ORIGIN),
    )
    site = next(iter(runner.sites))
    sockets = site._server.sockets  # type: ignore[union-attr]
    port = sockets[0].getsockname()[1]
    client_ssl = ssl.create_default_context()
    client_ssl.check_hostname = False
    client_ssl.verify_mode = ssl.CERT_NONE
    client = ClientSession(connector=TCPConnector(ssl=client_ssl))
    try:
        yield LiveGateway(server, client, f"wss://127.0.0.1:{port}/ws")
    finally:
        await client.close()
        await runner.cleanup()


class AuthWebSocket:
    def __init__(self, auth_message: dict, *, remote_ip: str = "127.0.0.1"):
        self._auth_message = _json_str(auth_message)
        self._auth_received = False
        self.remote_address = (remote_ip, 12345)
        self.sent: list[str] = []
        self.close_code: int | None = None

    async def recv(self):
        if self._auth_received:
            raise RuntimeError("authentication requested more than once")
        self._auth_received = True
        return self._auth_message

    async def send(self, message):
        self.sent.append(message)

    async def close(self, code=1000, reason=""):
        self.close_code = code


class AuthRelay(RelayMixin):
    def __init__(self, *, require_auth: bool = True):
        self.require_auth = require_auth
        self.auth_config = DJAuthConfig(
            vj_operators={
                "lighting": {"key_hash": hash_password("lighting-secret")},
                "video": {"key_hash": hash_password("video-secret")},
            }
        )
        self._browser_auth_attempts: dict[str, list[float]] = {}
        self._browser_auth_rate_limit_max = 5
        self._browser_auth_rate_limit_window = 60.0


@pytest.mark.asyncio
async def test_username_must_match_password_owner():
    relay = AuthRelay()
    websocket = AuthWebSocket(
        {
            "type": "vj_auth",
            "username": "lighting",
            "password": "video-secret",
        }
    )

    authenticated = await relay._negotiate_browser_auth(websocket)

    assert [mjson.decode(message) for message in websocket.sent] == [
        {"type": "auth_required"},
        {"type": "auth_error", "error": "Invalid username or password"},
    ]
    assert authenticated is False
    assert websocket.close_code == 4004


@pytest.mark.asyncio
async def test_username_is_required():
    relay = AuthRelay()
    websocket = AuthWebSocket(
        {
            "type": "vj_auth",
            "password": "lighting-secret",
        }
    )

    authenticated = await relay._negotiate_browser_auth(websocket)

    assert [mjson.decode(message) for message in websocket.sent] == [
        {"type": "auth_required"},
        {"type": "auth_error", "error": "Invalid username or password"},
    ]
    assert authenticated is False
    assert websocket.close_code == 4004


@pytest.mark.asyncio
async def test_sixth_failed_login_from_ip_is_rate_limited():
    relay = AuthRelay()
    sockets = [
        AuthWebSocket(
            {
                "type": "vj_auth",
                "username": "lighting",
                "password": "wrong-secret",
            },
            remote_ip="203.0.113.7",
        )
        for _ in range(6)
    ]

    for websocket in sockets:
        await relay._negotiate_browser_auth(websocket)

    assert [websocket.close_code for websocket in sockets[:5]] == [4004] * 5
    assert sockets[5].close_code == 4008
    assert mjson.decode(sockets[5].sent[1]) == {
        "type": "auth_error",
        "error": "Invalid username or password",
    }


@pytest.mark.asyncio
async def test_authenticated_browser_receives_challenge_then_success():
    relay = AuthRelay()
    websocket = AuthWebSocket(
        {
            "type": "vj_auth",
            "username": "lighting",
            "password": "lighting-secret",
        }
    )

    authenticated = await relay._negotiate_browser_auth(websocket)

    assert authenticated is True
    assert [mjson.decode(message) for message in websocket.sent] == [
        {"type": "auth_required"},
        {"type": "auth_success"},
    ]


@pytest.mark.asyncio
async def test_no_auth_browser_receives_success_without_sending_credentials():
    relay = AuthRelay(require_auth=False)
    websocket = AuthWebSocket({"type": "unused"})

    authenticated = await relay._negotiate_browser_auth(websocket)

    assert authenticated is True
    assert websocket._auth_received is False
    assert [mjson.decode(message) for message in websocket.sent] == [{"type": "auth_success"}]


async def test_real_tls_browser_authenticates_and_receives_initial_state(
    live_gateway: LiveGateway,
) -> None:
    socket = await live_gateway.client.ws_connect(
        live_gateway.websocket_url,
        origin=PUBLIC_ORIGIN,
    )
    try:
        auth_required = await socket.receive(timeout=1.0)
        assert auth_required.type is WSMsgType.TEXT
        assert mjson.decode(auth_required.data) == {"type": "auth_required"}

        await socket.send_json(
            {
                "type": "vj_auth",
                "username": "lighting",
                "password": "lighting-secret",
            }
        )
        auth_success = await socket.receive(timeout=1.0)
        initial_state = await socket.receive(timeout=1.0)

        assert mjson.decode(auth_success.data) == {"type": "auth_success"}
        assert mjson.decode(initial_state.data)["type"] == "vj_state"
        assert len(live_gateway.server._broadcast_clients) == 1
    finally:
        await socket.close()

    for _attempt in range(100):
        if not live_gateway.server._broadcast_clients:
            break
        await asyncio.sleep(0)
    assert live_gateway.server._broadcast_clients == set()
    assert live_gateway.server._browser_disconnects == 1


@pytest.mark.parametrize("origin", [None, "https://203.0.113.10:18080"])
async def test_real_tls_browser_rejects_missing_or_wrong_production_origin(
    live_gateway: LiveGateway,
    origin: str | None,
) -> None:
    kwargs = {} if origin is None else {"origin": origin}

    with pytest.raises(WSServerHandshakeError) as error:
        await live_gateway.client.ws_connect(live_gateway.websocket_url, **kwargs)

    assert error.value.status == 403
    assert live_gateway.server._browser_connects == 0


async def test_real_tls_browser_rejects_wrong_websocket_path(
    live_gateway: LiveGateway,
) -> None:
    with pytest.raises(WSServerHandshakeError) as error:
        await live_gateway.client.ws_connect(
            f"{live_gateway.websocket_url}/",
            origin=PUBLIC_ORIGIN,
        )

    assert error.value.status == 404
    assert live_gateway.server._browser_connects == 0
