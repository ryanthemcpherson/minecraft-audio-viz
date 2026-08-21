"""HTTP and WebSocket contract tests for the unified browser gateway."""

from __future__ import annotations

import ssl
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
from aiohttp import WSMessage, WSMsgType
from aiohttp.client_exceptions import WSServerHandshakeError
from aiohttp.test_utils import TestClient, TestServer
from yarl import URL

from vj_server.web_gateway import (
    RUNTIME_CONFIG_BODY,
    AiohttpBrowserSocket,
    UnifiedWebConfig,
    create_unified_web_app,
    resolve_contained_path,
    start_unified_web_gateway,
)

PUBLIC_ORIGIN = "https://203.0.113.9:8080"
PNG_BYTES = b"\x89PNG\r\n\x1a\nmcav"


async def _recording_browser_handler(socket: AiohttpBrowserSocket) -> None:
    await socket.send("connected")
    async for message in socket:
        await socket.send(message)


@pytest.fixture
async def gateway_client(tmp_path: Path) -> AsyncIterator[TestClient]:
    admin_root = tmp_path / "admin_panel"
    preview_root = tmp_path / "preview_tool" / "frontend"
    admin_root.mkdir()
    preview_root.mkdir(parents=True)

    (admin_root / "index.html").write_text("admin index", encoding="utf-8")
    (admin_root / "app.css").write_text("body {}", encoding="utf-8")
    (admin_root / "app.js").write_text("export {};", encoding="utf-8")
    (admin_root / "logo.png").write_bytes(PNG_BYTES)
    (preview_root / "index.html").write_text("preview index", encoding="utf-8")
    (tmp_path / "outside-secret.txt").write_text("outside secret", encoding="utf-8")

    app = create_unified_web_app(
        _recording_browser_handler,
        UnifiedWebConfig(project_root=tmp_path, public_origin=PUBLIC_ORIGIN),
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        yield client
    finally:
        await client.close()


def _assert_no_store(response: Any) -> None:
    assert response.headers.getall("Cache-Control") == ["no-store"]


@pytest.mark.parametrize("method", ["GET", "HEAD"])
async def test_unified_gateway_serves_admin_with_no_store(
    method: str,
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.request(method, "/")

    assert response.status == 200
    _assert_no_store(response)
    assert response.content_type == "text/html"
    if method == "HEAD":
        assert await response.read() == b""
        assert response.content_length == len(b"admin index")
    else:
        assert await response.read() == b"admin index"


@pytest.mark.parametrize("method", ["GET", "HEAD"])
async def test_unified_gateway_redirects_exact_preview_path(
    method: str,
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.request(method, "/preview", allow_redirects=False)

    assert response.status == 301
    assert response.headers["Location"] == "/preview/"
    _assert_no_store(response)
    if method == "HEAD":
        assert await response.read() == b""


@pytest.mark.parametrize("method", ["GET", "HEAD"])
async def test_unified_gateway_serves_preview_index(
    method: str,
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.request(method, "/preview/")

    assert response.status == 200
    _assert_no_store(response)
    if method == "HEAD":
        assert await response.read() == b""
    else:
        assert await response.read() == b"preview index"


@pytest.mark.parametrize(
    ("path", "content_type", "body"),
    [
        ("/app.css", "text/css", b"body {}"),
        ("/app.js", "text/javascript", b"export {};"),
        ("/logo.png", "image/png", PNG_BYTES),
    ],
)
async def test_unified_gateway_sets_static_mime_types(
    path: str,
    content_type: str,
    body: bytes,
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.get(path)

    assert response.status == 200
    assert response.content_type == content_type
    assert await response.read() == body
    _assert_no_store(response)


@pytest.mark.parametrize(
    "path",
    [
        "/../outside-secret.txt",
        "/preview/../../outside-secret.txt",
        "/%2e%2e/outside-secret.txt",
        "/preview/%2e%2e%5coutside-secret.txt",
        "/administrator/index.html",
        "/%00outside-secret.txt",
        "/preview/%2foutside-secret.txt",
    ],
)
async def test_unified_gateway_contains_static_paths(
    path: str,
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.get(URL(path, encoded=True))
    body = await response.read()

    assert response.status == 404
    _assert_no_store(response)
    assert len(body) <= 128
    assert b"outside secret" not in body


async def test_unified_gateway_rejects_symlink_escape(
    tmp_path: Path,
    gateway_client: TestClient,
) -> None:
    outside_file = tmp_path / "outside-linked-secret.txt"
    outside_file.write_text("linked secret", encoding="utf-8")
    (tmp_path / "admin_panel" / "escape.txt").symlink_to(outside_file)

    response = await gateway_client.get("/escape.txt")
    body = await response.read()

    assert response.status == 404
    _assert_no_store(response)
    assert b"linked secret" not in body


@pytest.mark.parametrize(
    "raw_path",
    [
        "//server/share/secret.txt",
        "CON",
        "AUX.txt",
        "file.txt::$DATA",
        "trailing.",
    ],
)
def test_unified_gateway_preserves_cross_platform_path_rejections(
    tmp_path: Path,
    raw_path: str,
) -> None:
    target = tmp_path.joinpath(*raw_path.lstrip("/").split("/"))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("must not be served", encoding="utf-8")

    assert resolve_contained_path(tmp_path, raw_path) is None


@pytest.mark.parametrize(
    "path",
    [
        "/missing.txt",
        "/preview/missing.txt",
        "/administrator/index.html",
    ],
)
async def test_unified_gateway_404_does_not_disclose_filesystem_paths(
    path: str,
    gateway_client: TestClient,
    tmp_path: Path,
) -> None:
    response = await gateway_client.get(path)
    body = await response.read()

    assert response.status == 404
    _assert_no_store(response)
    assert len(body) <= 128
    assert str(tmp_path).encode() not in body
    assert b"admin_panel" not in body
    assert b"preview_tool" not in body


@pytest.mark.parametrize("method", ["POST", "PUT", "DELETE", "OPTIONS"])
async def test_unified_gateway_rejects_non_read_static_methods(
    method: str,
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.request(method, "/app.js")

    assert response.status == 404
    _assert_no_store(response)
    assert len(await response.read()) <= 128


async def test_runtime_config_is_fixed_and_not_cached(
    gateway_client: TestClient,
) -> None:
    response = await gateway_client.get("/runtime-config.js")

    assert response.status == 200
    assert response.content_type == "text/javascript"
    assert response.content_length == len(RUNTIME_CONFIG_BODY)
    _assert_no_store(response)
    assert await response.read() == (
        b"window.MCAV_RUNTIME_CONFIG = Object.freeze({"
        b'browserWebSocketMode: "same-origin",'
        b'browserWebSocketPath: "/ws"'
        b"});\n"
    )


@pytest.mark.parametrize("origin", [None, "https://203.0.113.10:8080"])
async def test_browser_websocket_rejects_missing_or_wrong_origin(
    origin: str | None,
    gateway_client: TestClient,
) -> None:
    headers = {} if origin is None else {"Origin": origin}

    with pytest.raises(WSServerHandshakeError) as error:
        await gateway_client.ws_connect("/ws", headers=headers)

    assert error.value.status == 403
    assert error.value.headers.getall("Cache-Control") == ["no-store"]


async def test_browser_websocket_adapts_text_binary_and_remote_address(
    gateway_client: TestClient,
) -> None:
    socket = await gateway_client.ws_connect("/ws", headers={"Origin": PUBLIC_ORIGIN})
    try:
        assert socket._response.headers.getall("Cache-Control") == ["no-store"]
        connected = await socket.receive()
        assert connected.type is WSMsgType.TEXT
        assert connected.data == "connected"

        await socket.send_str("text frame")
        echoed_text = await socket.receive()
        assert echoed_text.type is WSMsgType.TEXT
        assert echoed_text.data == "text frame"

        await socket.send_bytes(b"binary frame")
        echoed_binary = await socket.receive()
        assert echoed_binary.type is WSMsgType.BINARY
        assert echoed_binary.data == b"binary frame"
    finally:
        await socket.close()


class _FakeWebSocketResponse:
    def __init__(self, *messages: WSMessage) -> None:
        self.messages = list(messages)
        self.sent: list[tuple[str, str | bytes]] = []
        self.close_arguments: tuple[int, bytes] | None = None

    async def send_str(self, message: str) -> None:
        self.sent.append(("text", message))

    async def send_bytes(self, message: bytes) -> None:
        self.sent.append(("binary", message))

    async def receive(self) -> WSMessage:
        return self.messages.pop(0)

    async def close(self, *, code: int, message: bytes) -> None:
        self.close_arguments = (code, message)

    def get_extra_info(self, name: str, default: Any = None) -> Any:
        return ("127.0.0.1", 54321) if name == "peername" else default


async def test_browser_socket_send_close_and_remote_address() -> None:
    response = _FakeWebSocketResponse()
    socket = AiohttpBrowserSocket(response)  # type: ignore[arg-type]

    await socket.send("text")
    await socket.send(b"binary")
    await socket.close(4003, "finished")

    assert response.sent == [("text", "text"), ("binary", b"binary")]
    assert response.close_arguments == (4003, b"finished")
    assert socket.remote_address == ("127.0.0.1", 54321)


async def test_browser_socket_recv_returns_text_and_binary() -> None:
    response = _FakeWebSocketResponse(
        WSMessage(WSMsgType.TEXT, "text", ""),
        WSMessage(WSMsgType.BINARY, b"binary", ""),
    )
    socket = AiohttpBrowserSocket(response)  # type: ignore[arg-type]

    assert await socket.recv() == "text"
    assert await socket.recv() == b"binary"


@pytest.mark.parametrize("message_type", [WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED])
async def test_browser_socket_normal_closure_ends_iteration(message_type: WSMsgType) -> None:
    response = _FakeWebSocketResponse(WSMessage(message_type, None, ""))
    socket = AiohttpBrowserSocket(response)  # type: ignore[arg-type]

    with pytest.raises(StopAsyncIteration):
        await anext(socket)


async def test_browser_socket_error_frame_raises_connection_error() -> None:
    cause = RuntimeError("transport failed")
    response = _FakeWebSocketResponse(WSMessage(WSMsgType.ERROR, cause, ""))
    socket = AiohttpBrowserSocket(response)  # type: ignore[arg-type]

    with pytest.raises(ConnectionError, match="transport failed"):
        await socket.recv()


async def test_start_unified_web_gateway_returns_live_runner(tmp_path: Path) -> None:
    (tmp_path / "admin_panel").mkdir()
    (tmp_path / "admin_panel" / "index.html").write_text("live", encoding="utf-8")
    (tmp_path / "preview_tool" / "frontend").mkdir(parents=True)
    config = UnifiedWebConfig(project_root=tmp_path, public_origin=PUBLIC_ORIGIN)
    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)

    runner = await start_unified_web_gateway(
        _recording_browser_handler,
        "127.0.0.1",
        0,
        ssl_context,
        config,
    )
    try:
        assert runner.sites
        assert all(site._server is not None for site in runner.sites)
        assert all(site._ssl_context is ssl_context for site in runner.sites)
    finally:
        await runner.cleanup()
