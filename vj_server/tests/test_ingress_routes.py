from __future__ import annotations

import asyncio
import datetime
import ipaddress
import ssl
from pathlib import Path

import pytest
import pytest_asyncio
from aiohttp import ClientSession, web
from aiohttp.test_utils import TestClient, TestServer

from vj_server.ingress.app import IngressServer, _IngressSite, create_ingress_application
from vj_server.ingress.limits import IngressLimits
from vj_server.ingress.security import ConnectionLimiter
from vj_server.setup import SetupManager


def create_project(root: Path) -> None:
    admin = root / "admin_panel"
    admin_js = admin / "js"
    admin_css = admin / "css"
    preview = root / "preview_tool" / "frontend"
    assets = root / "assets"
    admin.mkdir(parents=True)
    admin_js.mkdir()
    admin_css.mkdir()
    preview.mkdir(parents=True)
    assets.mkdir()
    (admin / "index.html").write_text("admin", encoding="utf-8")
    (admin / "setup.html").write_text("setup", encoding="utf-8")
    (admin_js / "setup.js").write_text("setup script", encoding="utf-8")
    (admin_js / "admin-app.js").write_text("private admin script", encoding="utf-8")
    (admin_css / "setup.css").write_text("setup style", encoding="utf-8")
    (admin_css / "mcav-tokens.css").write_text("tokens", encoding="utf-8")
    (admin / "mcav-medium-square.png").write_bytes(b"setup logo")
    (admin / "mcav.ico").write_bytes(b"setup icon")
    (preview / "index.html").write_text("preview", encoding="utf-8")


def create_test_tls_context(root: Path) -> ssl.SSLContext:
    cryptography = pytest.importorskip("cryptography")
    del cryptography
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.datetime.now(datetime.UTC)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=1))
        .not_valid_after(now + datetime.timedelta(days=1))
        .add_extension(
            x509.SubjectAlternativeName(
                [
                    x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                ]
            ),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    certificate_path = root / "test.crt"
    key_path = root / "test.key"
    certificate_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certificate_path, key_path)
    return context


@pytest_asyncio.fixture
async def starting_client(tmp_path: Path) -> TestClient:
    create_project(tmp_path)
    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(),
        health_provider=lambda: False,
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        yield client
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_health_is_minimal_and_methods_are_bounded(starting_client: TestClient) -> None:
    response = await starting_client.get("/healthz")
    post = await starting_client.post("/healthz", json={"ignored": True})
    static_post = await starting_client.post("/", data=b"ignored")

    assert response.status == 503
    assert await response.json() == {"status": "starting"}
    assert set((await response.json()).keys()) == {"status"}
    assert post.status == 405
    assert static_post.status == 405


@pytest_asyncio.fixture
async def setup_client(tmp_path: Path) -> tuple[TestClient, SetupManager, str]:
    create_project(tmp_path)
    state = tmp_path / "state"
    state.mkdir()
    (state / "auth.json").write_text(
        '{"djs":{},"vj_operators":{}}\n',
        encoding="utf-8",
    )
    setup = SetupManager(state, renderer_secret=b"r" * 32)
    offer = setup.rotate()
    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(),
        health_provider=lambda: True,
        setup_manager=setup,
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        yield client, setup, offer.token
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_setup_routes_verify_and_create_admin_without_caching(
    setup_client: tuple[TestClient, SetupManager, str],
) -> None:
    client, setup, token = setup_client

    status = await client.get("/setup/status")
    verified = await client.post("/setup/verify", json={"token": token})
    created = await client.post(
        "/setup/admin",
        json={
            "token": token,
            "username": "VJ_Admin",
            "password": "correct horse battery",
        },
    )

    assert status.status == 200
    assert await status.json() == {"status": "available"}
    assert status.headers["Cache-Control"] == "no-store"
    assert verified.status == 200
    assert await verified.json() == {"valid": True}
    assert created.status == 201
    assert await created.json() == {"status": "complete", "username": "vj_admin"}
    assert setup.status() == "complete"


@pytest.mark.asyncio
async def test_setup_shell_uses_an_exact_allowlist_and_disappears_after_setup(
    setup_client: tuple[TestClient, SetupManager, str],
) -> None:
    client, setup, token = setup_client

    redirect = await client.get("/setup", allow_redirects=False)
    shell = await client.get("/setup/")
    script = await client.get("/setup/js/setup.js")
    private_asset = await client.get("/setup/js/admin-app.js")
    alternate_setup_assets = [
        "/setup.html",
        "/%73etup.html",
        "/js/setup.js",
        "/css/setup.css",
    ]
    alternate_before_setup = [await client.get(path) for path in alternate_setup_assets]

    assert redirect.status == 308
    assert redirect.headers["Location"] == "/setup/"
    assert redirect.headers["Cache-Control"] == "no-store"
    assert shell.status == 200
    assert await shell.text() == "setup"
    assert shell.headers["Cache-Control"] == "no-store"
    assert shell.headers["Content-Security-Policy"] == (
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
        "connect-src 'self'; font-src 'self'; base-uri 'none'; form-action 'self'; "
        "frame-ancestors 'none'"
    )
    assert script.status == 200
    assert script.headers["Cache-Control"] == "no-store"
    assert private_asset.status == 404
    assert all(response.status == 404 for response in alternate_before_setup)
    assert all(
        response.headers["Cache-Control"] == "no-store" for response in alternate_before_setup
    )

    await setup.create_admin(token, "operator", "correct horse battery")

    assert (await client.get("/setup/", allow_redirects=False)).status == 404
    assert (await client.get("/setup", allow_redirects=False)).status == 404
    alternate_after_setup = [await client.get(path) for path in alternate_setup_assets]
    assert all(response.status == 404 for response in alternate_after_setup)


@pytest.mark.asyncio
async def test_setup_routes_use_one_generic_invalid_token_response(
    setup_client: tuple[TestClient, SetupManager, str],
) -> None:
    client, setup, _token = setup_client
    setup.rotate()

    verified = await client.post("/setup/verify", json={"token": "wrong"})
    created = await client.post(
        "/setup/admin",
        json={
            "token": "wrong",
            "username": "operator",
            "password": "correct horse battery",
        },
    )
    malformed = await client.post(
        "/setup/admin",
        json={"token": "wrong", "username": "!", "password": "short"},
    )

    assert verified.status == created.status == malformed.status == 401
    assert (
        await verified.json()
        == await created.json()
        == await malformed.json()
        == {"error": "invalid or expired setup token"}
    )
    assert verified.headers["Cache-Control"] == "no-store"
    assert created.headers["Cache-Control"] == "no-store"


@pytest.mark.asyncio
async def test_setup_routes_bound_payload_origin_and_ip_rate(
    setup_client: tuple[TestClient, SetupManager, str],
) -> None:
    client, _setup, token = setup_client

    oversized = await client.post(
        "/setup/verify",
        data=b"x" * 4097,
        headers={"Content-Type": "application/json"},
    )
    wrong_origin = await client.post(
        "/setup/verify",
        json={"token": token},
        headers={"Origin": "https://attacker.example"},
    )
    accepted = [await client.post("/setup/verify", json={"token": token}) for _ in range(10)]
    limited = await client.post("/setup/verify", json={"token": token})

    assert oversized.status == 413
    assert oversized.headers["Cache-Control"] == "no-store"
    assert wrong_origin.status == 403
    assert wrong_origin.headers["Cache-Control"] == "no-store"
    assert all(response.status == 200 for response in accepted)
    assert limited.status == 429
    assert limited.headers["Cache-Control"] == "no-store"


@pytest.mark.asyncio
async def test_started_loopback_ingress_reports_bound_address_and_stops(tmp_path: Path) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
    )

    await server.start()
    try:
        assert server.healthy
        host, port = server.bound_address
        assert host == "127.0.0.1"
        assert port > 0
        async with ClientSession() as session:
            async with session.get(f"http://{host}:{port}/healthz") as response:
                assert response.status == 200
                assert await response.json() == {"status": "ok"}
    finally:
        await server.stop()

    assert not server.healthy
    assert server.bound_address is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("host", "allow_insecure"),
    [
        ("127.0.0.1", False),
        ("0.0.0.0", True),
        ("localhost", False),
        ("::1", False),
    ],
)
async def test_plaintext_policy_fails_closed(
    tmp_path: Path,
    host: str,
    allow_insecure: bool,
) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host=host,
        port=0,
        ssl_context=None,
        allow_insecure_loopback=allow_insecure,
    )

    with pytest.raises(ValueError, match="TLS"):
        await server.start()

    assert not server.healthy
    assert server.bound_address is None


@pytest.mark.asyncio
async def test_connection_limiter_never_exceeds_capacity() -> None:
    limiter = ConnectionLimiter(1)
    first_entered = asyncio.Event()
    release_first = asyncio.Event()
    outcomes: list[bool] = []

    async def first() -> None:
        async with limiter.reserve() as accepted:
            outcomes.append(accepted)
            first_entered.set()
            await release_first.wait()

    task = asyncio.create_task(first())
    await first_entered.wait()
    async with limiter.reserve() as accepted:
        outcomes.append(accepted)
    release_first.set()
    await task
    async with limiter.reserve() as accepted:
        outcomes.append(accepted)

    assert outcomes == [True, False, True]
    assert limiter.active == 0


@pytest.mark.asyncio
async def test_application_connection_limit_returns_service_unavailable(tmp_path: Path) -> None:
    create_project(tmp_path)
    request_entered = asyncio.Event()
    release_request = asyncio.Event()

    async def slow(_request: web.Request) -> web.Response:
        request_entered.set()
        await release_request.wait()
        return web.Response(text="done")

    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(max_connections=1),
        health_provider=lambda: True,
        extra_routes=[web.get("/slow", slow)],
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        first = asyncio.create_task(client.get("/slow"))
        await request_entered.wait()
        rejected = await client.get("/healthz")
        rejected_body = await rejected.json()
        release_request.set()
        completed = await first
    finally:
        release_request.set()
        await client.close()

    assert rejected.status == 503
    assert rejected_body == {"status": "busy"}
    assert completed.status == 200


async def _read_after_rejected_connection(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> bytes:
    try:
        writer.write(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\n\r\n")
        await writer.drain()
        return await asyncio.wait_for(reader.read(1), timeout=1.0)
    except (BrokenPipeError, ConnectionResetError):
        return b""


@pytest.mark.asyncio
async def test_transport_connection_limit_rejects_slow_pre_request_socket(
    tmp_path: Path,
) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
        limits=IngressLimits(max_connections=1),
    )
    await server.start()
    host, port = server.bound_address
    first_reader, first_writer = await asyncio.open_connection(host, port)
    second_reader, second_writer = await asyncio.open_connection(host, port)
    try:
        rejected = await _read_after_rejected_connection(second_reader, second_writer)
        assert rejected == b""
    finally:
        first_writer.close()
        second_writer.close()
        await first_writer.wait_closed()
        try:
            await second_writer.wait_closed()
        except (BrokenPipeError, ConnectionResetError):
            pass
        del first_reader
        await server.stop()


@pytest.mark.asyncio
async def test_initial_header_timeout_closes_slow_socket(tmp_path: Path) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
        limits=IngressLimits(initial_header_timeout_seconds=0.05),
    )
    await server.start()
    host, port = server.bound_address
    reader, writer = await asyncio.open_connection(host, port)
    try:
        writer.write(b"GET /healthz HTTP/1.1\r\nHost:")
        await writer.drain()
        assert await asyncio.wait_for(reader.read(1), timeout=0.5) == b""
    finally:
        writer.close()
        await writer.wait_closed()
        await server.stop()


@pytest.mark.asyncio
async def test_fragmented_oversized_header_is_rejected(tmp_path: Path) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
        limits=IngressLimits(
            max_header_field_bytes=64,
            max_header_count=2,
            max_header_bytes=128,
        ),
    )
    await server.start()
    host, port = server.bound_address
    reader, writer = await asyncio.open_connection(host, port)
    try:
        writer.write(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\nX-Fill: ")
        await writer.drain()
        for _ in range(5):
            writer.write(b"a" * 32)
            await writer.drain()
        response = await asyncio.wait_for(reader.read(512), timeout=1.0)
        assert b" 400 " in response
        assert b'"status":"ok"' not in response
    finally:
        writer.close()
        await writer.wait_closed()
        await server.stop()


def test_header_count_and_aggregate_byte_limits_are_consistent() -> None:
    with pytest.raises(ValueError, match="aggregate"):
        IngressLimits(
            max_header_field_bytes=1_024,
            max_header_count=32,
            max_header_bytes=16_384,
        )


@pytest.mark.asyncio
async def test_tls_handshake_timeout_and_admission_bound_stalled_clients(
    tmp_path: Path,
) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=create_test_tls_context(tmp_path),
        limits=IngressLimits(
            max_connections=1,
            tls_handshake_timeout_seconds=0.1,
        ),
    )
    await server.start()
    host, port = server.bound_address
    first_reader, first_writer = await asyncio.open_connection(host, port)
    second_reader, second_writer = await asyncio.open_connection(host, port)
    try:
        rejected = await _read_after_rejected_connection(second_reader, second_writer)
        assert rejected == b""
        assert await asyncio.wait_for(first_reader.read(1), timeout=0.5) == b""

        client_context = ssl.create_default_context()
        client_context.check_hostname = False
        client_context.verify_mode = ssl.CERT_NONE
        for _ in range(20):
            try:
                reader, writer = await asyncio.open_connection(
                    host,
                    port,
                    ssl=client_context,
                    server_hostname="localhost",
                )
                break
            except (ConnectionError, ssl.SSLError):
                await asyncio.sleep(0.05)
        else:
            pytest.fail("TLS admission was not released after handshake timeout")
        writer.write(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        await writer.drain()
        response = await asyncio.wait_for(reader.read(), timeout=1.0)
        assert b" 200 " in response
        writer.close()
        await writer.wait_closed()
    finally:
        first_writer.close()
        second_writer.close()
        await first_writer.wait_closed()
        try:
            await second_writer.wait_closed()
        except (BrokenPipeError, ConnectionResetError):
            pass
        await server.stop()


@pytest.mark.asyncio
async def test_stop_serializes_with_in_progress_start(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
    )
    entered_start = asyncio.Event()
    release_start = asyncio.Event()
    original_start = _IngressSite.start

    async def delayed_start(site: _IngressSite) -> None:
        entered_start.set()
        await release_start.wait()
        await original_start(site)

    monkeypatch.setattr(_IngressSite, "start", delayed_start)
    start_task = asyncio.create_task(server.start())
    await entered_start.wait()
    stop_task = asyncio.create_task(server.stop())
    await asyncio.sleep(0)
    assert not stop_task.done()

    release_start.set()
    await start_task
    await stop_task

    assert not server.healthy
    assert server.bound_address is None


@pytest.mark.asyncio
async def test_concurrent_starts_leave_only_one_tracked_listener(tmp_path: Path) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
    )
    results = await asyncio.gather(server.start(), server.start(), return_exceptions=True)
    try:
        assert sum(result is None for result in results) == 1
        errors = [result for result in results if isinstance(result, RuntimeError)]
        assert len(errors) == 1
        assert "already started" in str(errors[0])
    finally:
        await server.stop()


@pytest.mark.asyncio
async def test_cancelled_start_cleans_runner_and_allows_retry(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    create_project(tmp_path)
    server = IngressServer(
        tmp_path,
        host="127.0.0.1",
        port=0,
        ssl_context=None,
        allow_insecure_loopback=True,
    )
    entered_start = asyncio.Event()
    never_release = asyncio.Event()
    original_start = _IngressSite.start

    async def blocked_start(_site: _IngressSite) -> None:
        entered_start.set()
        await never_release.wait()

    monkeypatch.setattr(_IngressSite, "start", blocked_start)
    start_task = asyncio.create_task(server.start())
    await entered_start.wait()
    start_task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await start_task

    assert not server.healthy
    assert server.bound_address is None

    monkeypatch.setattr(_IngressSite, "start", original_start)
    await server.start()
    try:
        assert server.healthy
        assert server.bound_address is not None
    finally:
        await server.stop()
