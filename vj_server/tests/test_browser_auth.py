"""Focused browser authentication boundary and real gateway tests."""

import asyncio
import socket
import ssl
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path

import msgspec.json as mjson
import pytest
from aiohttp import ClientSession, TCPConnector, WSMsgType
from aiohttp.client_exceptions import WSServerHandshakeError
from websockets import connect as ws_connect
from websockets.exceptions import InvalidHandshake

import vj_server.vj_server as vj_server_module
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


@pytest.fixture
def tls_context(tmp_path: Path) -> ssl.SSLContext:
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
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert_file, key_file)
    return context


@dataclass
class LiveGateway:
    server: VJServer
    client: ClientSession
    websocket_url: str


@pytest.fixture
async def live_gateway(
    tmp_path: Path,
    tls_context: ssl.SSLContext,
) -> AsyncIterator[LiveGateway]:
    (tmp_path / "admin_panel").mkdir()
    (tmp_path / "preview_tool" / "frontend").mkdir(parents=True)
    server = VJServer(
        project_root=tmp_path,
        auth_config=DJAuthConfig(
            vj_operators={
                "lighting": {"key_hash": hash_password("lighting-secret")},
            }
        ),
        metrics_port=None,
        show_spectrograph=False,
    )
    server.server_ssl_context = tls_context
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


@dataclass
class LiveDJListener:
    server: VJServer
    secure_url: str
    plaintext_url: str
    client_ssl_context: ssl.SSLContext


@pytest.mark.asyncio
async def test_vj_dj_listener_binds_ipv6_when_supported(
    monkeypatch: pytest.MonkeyPatch,
    tls_context: ssl.SSLContext,
) -> None:
    if not socket.has_ipv6:
        pytest.skip("host has no IPv6 support")
    probe = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    try:
        probe.bind(("::1", 0))
    except OSError:
        pytest.skip("IPv6 loopback is unavailable")
    finally:
        probe.close()

    monkeypatch.setattr(vj_server_module, "build_server_ssl_context", lambda *_args: tls_context)

    class FakeGatewayRunner:
        async def cleanup(self) -> None:
            return None

    async def fake_gateway(*_args, **_kwargs):
        return FakeGatewayRunner()

    real_ws_serve = vj_server_module.ws_serve
    dj_listener = None
    listener_started = asyncio.Event()
    main_loop_release = asyncio.Event()

    async def capture_listener(handler, host, port, **kwargs):
        nonlocal dj_listener
        listener = await real_ws_serve(handler, host, port, **kwargs)
        dj_listener = listener
        listener_started.set()
        return listener

    async def no_op() -> None:
        return None

    async def main_loop() -> None:
        await main_loop_release.wait()

    monkeypatch.setattr(vj_server_module, "start_unified_web_gateway", fake_gateway)
    monkeypatch.setattr(vj_server_module, "ws_serve", capture_listener)
    server = VJServer(
        dj_host="::1",
        dj_port=0,
        http_port=8080,
        http_host="::1",
        tls_cert="fixture.crt",
        tls_key="fixture.key",
        unified_web=True,
        public_origin="https://[::1]:8080",
        metrics_port=None,
        show_spectrograph=False,
    )
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = main_loop

    task = asyncio.create_task(server.run())
    try:
        await asyncio.wait_for(listener_started.wait(), timeout=1.0)
        assert dj_listener is not None
        sockets = dj_listener.sockets
        assert sockets
        assert all(bound.family == socket.AF_INET6 for bound in sockets)
        assert all(bound.getsockname()[0] == "::1" for bound in sockets)
    finally:
        main_loop_release.set()
        await asyncio.wait_for(task, timeout=1.0)


@pytest.fixture
async def live_dj_listener(
    monkeypatch: pytest.MonkeyPatch,
    tls_context: ssl.SSLContext,
) -> AsyncIterator[LiveDJListener]:
    server = VJServer(
        dj_port=0,
        broadcast_port=0,
        http_port=0,
        auth_config=DJAuthConfig(
            djs={
                "tls-dj": {
                    "key_hash": hash_password("tls-secret"),
                    "name": "TLS DJ",
                    "priority": 7,
                }
            }
        ),
        metrics_port=None,
        show_spectrograph=False,
    )
    server.server_ssl_context = tls_context
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False

    real_ws_serve = vj_server_module.ws_serve
    dj_listener_started = asyncio.Event()
    dj_listener = None

    async def capture_listener(handler, host, port, **kwargs):
        nonlocal dj_listener
        listener = await real_ws_serve(handler, host, port, **kwargs)
        if handler == server._handle_dj_connection:
            dj_listener = listener
            dj_listener_started.set()
        return listener

    async def no_op() -> None:
        return None

    async def wait_for_cancellation() -> None:
        await asyncio.Future()

    monkeypatch.setattr(vj_server_module, "ws_serve", capture_listener)
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = wait_for_cancellation

    run_task = asyncio.create_task(server.run())
    try:
        await asyncio.wait_for(dj_listener_started.wait(), timeout=1.0)
        assert dj_listener is not None
        sockets = dj_listener.sockets
        port = sockets[0].getsockname()[1]
        client_ssl_context = ssl.create_default_context()
        client_ssl_context.check_hostname = False
        client_ssl_context.verify_mode = ssl.CERT_NONE
        yield LiveDJListener(
            server=server,
            secure_url=f"wss://127.0.0.1:{port}",
            plaintext_url=f"ws://127.0.0.1:{port}",
            client_ssl_context=client_ssl_context,
        )
    finally:
        run_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(run_task, timeout=2.0)


@pytest.mark.asyncio
async def test_dj_listener_uses_server_tls_context(
    monkeypatch: pytest.MonkeyPatch,
    tls_context: ssl.SSLContext,
) -> None:
    calls = []
    main_loop_entered = asyncio.Event()

    class FakeClosableServer:
        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    async def fake_serve(handler, host, port, **kwargs):
        calls.append((handler, host, port, kwargs))
        return FakeClosableServer()

    async def no_op() -> None:
        return None

    async def wait_for_cancellation() -> None:
        main_loop_entered.set()
        await asyncio.Future()

    monkeypatch.setattr(vj_server_module, "ws_serve", fake_serve)
    server = VJServer(
        dj_port=25808,
        http_port=0,
        metrics_port=None,
        show_spectrograph=False,
    )
    server.server_ssl_context = tls_context
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = wait_for_cancellation

    task = asyncio.create_task(server.run())
    await asyncio.wait_for(main_loop_entered.wait(), timeout=1.0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    dj_call = next(call for call in calls if call[2] == 25808)
    assert dj_call[3]["ssl"] is tls_context


@pytest.mark.asyncio
async def test_tls_dj_listener_logs_wss(
    monkeypatch: pytest.MonkeyPatch,
    tls_context: ssl.SSLContext,
    caplog: pytest.LogCaptureFixture,
) -> None:
    class FakeClosableServer:
        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    async def fake_serve(*_args, **_kwargs):
        return FakeClosableServer()

    async def no_op() -> None:
        return None

    monkeypatch.setattr(vj_server_module, "ws_serve", fake_serve)
    server = VJServer(
        dj_port=25808,
        http_port=0,
        metrics_port=None,
        show_spectrograph=False,
    )
    server.server_ssl_context = tls_context
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = no_op

    with caplog.at_level("INFO", logger="vj_server"):
        await server.run()

    assert "DJ WebSocket server: wss://0.0.0.0:25808" in caplog.messages


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


async def test_real_tls_dj_listener_completes_credential_authentication(
    live_dj_listener: LiveDJListener,
) -> None:
    async with ws_connect(
        live_dj_listener.secure_url,
        ssl=live_dj_listener.client_ssl_context,
        open_timeout=1.0,
        close_timeout=1.0,
    ) as socket:
        await socket.send(
            mjson.encode(
                {
                    "type": "dj_auth",
                    "dj_id": "tls-dj",
                    "dj_key": "tls-secret",
                    "dj_name": "TLS DJ",
                }
            )
        )
        auth_success = mjson.decode(await asyncio.wait_for(socket.recv(), timeout=1.0))
        clock_sync_request = mjson.decode(await asyncio.wait_for(socket.recv(), timeout=1.0))
        server_time = clock_sync_request["server_time"]
        await socket.send(
            mjson.encode(
                {
                    "type": "clock_sync_response",
                    "dj_recv_time": server_time,
                    "dj_send_time": server_time,
                }
            )
        )
        stream_route = mjson.decode(await asyncio.wait_for(socket.recv(), timeout=1.0))

        assert auth_success["type"] == "auth_success"
        assert auth_success["dj_id"] == "tls-dj"
        assert clock_sync_request["type"] == "clock_sync_request"
        assert stream_route["type"] == "stream_route"
        assert live_dj_listener.server._dj_connects == 1


async def test_real_tls_dj_listener_rejects_plaintext_websocket(
    live_dj_listener: LiveDJListener,
) -> None:
    with pytest.raises(InvalidHandshake):
        async with ws_connect(
            live_dj_listener.plaintext_url,
            open_timeout=1.0,
            close_timeout=1.0,
        ):
            pass


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
