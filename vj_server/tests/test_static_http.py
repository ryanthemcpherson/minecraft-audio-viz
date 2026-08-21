import asyncio
import http.client
import http.server
import socketserver
import sys
import threading
import urllib.parse
from contextlib import contextmanager
from http import HTTPStatus
from inspect import signature
from pathlib import Path
from typing import Iterator

import pytest

import vj_server.models as models
import vj_server.vj_server as vj_server_module
from vj_server import cli as cli_module
from vj_server.cli import vj_server as modern_cli_main
from vj_server.models import (
    _REJECTED_STATIC_PATH,
    MultiDirectoryHandler,
    _make_directory_handler,
    _make_threaded_http_server_class,
    _resolve_static_path,
    _static_path_parts,
    run_http_server,
)
from vj_server.vj_server import VJServer
from vj_server.vj_server import main as legacy_main


def _build_handler(
    implementation: str,
    directory_map: dict[str, str],
    default_directory: Path,
) -> http.server.SimpleHTTPRequestHandler:
    if implementation == "factory":
        handler_class = _make_directory_handler(directory_map)
    else:
        handler_class = MultiDirectoryHandler

    handler = object.__new__(handler_class)
    handler.directory = str(default_directory)
    if implementation == "legacy":
        handler.directory_map = directory_map
    return handler


def _rejected_path(root: Path) -> str:
    return str(root.resolve() / _REJECTED_STATIC_PATH)


def _build_handler_class(
    implementation: str,
    directory_map: dict[str, str],
) -> type[http.server.SimpleHTTPRequestHandler]:
    if implementation == "factory":
        return _make_directory_handler(directory_map)

    class _LegacyHandler(MultiDirectoryHandler):
        pass

    _LegacyHandler.directory_map = directory_map
    return _LegacyHandler


@contextmanager
def _running_http_server(
    handler_class: type[http.server.SimpleHTTPRequestHandler],
) -> Iterator[tuple[str, int]]:
    with socketserver.TCPServer(("127.0.0.1", 0), handler_class) as server:
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        try:
            host, port = server.server_address
            yield str(host), int(port)
        finally:
            server.shutdown()
            server_thread.join(timeout=5)


def _http_get(address: tuple[str, int], path: str) -> tuple[int, bytes, str | None]:
    connection = http.client.HTTPConnection(*address, timeout=5)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        return response.status, response.read(), response.getheader("Location")
    finally:
        connection.close()


def _create_sentinel_collision(root: Path, collision_kind: str) -> None:
    sentinel = root / _REJECTED_STATIC_PATH
    if collision_kind == "file":
        sentinel.write_text("sentinel file must not be served", encoding="utf-8")
    else:
        sentinel.mkdir()
        (sentinel / "index.html").write_text(
            "sentinel directory must not be served",
            encoding="utf-8",
        )


def _make_capturing_tcp_server(
    bind_attempts: list[tuple[str, int]],
    handler_classes: list[type[http.server.SimpleHTTPRequestHandler]] | None = None,
) -> type[object]:
    class CapturingTCPServer:
        def __init__(
            self,
            server_address: tuple[str, int],
            handler_class: type[http.server.SimpleHTTPRequestHandler],
        ) -> None:
            bind_attempts.append(server_address)
            if handler_classes is not None:
                handler_classes.append(handler_class)

        def __enter__(self) -> "CapturingTCPServer":
            return self

        def __exit__(self, *args: object) -> None:
            pass

        def serve_forever(self) -> None:
            pass

    return CapturingTCPServer


@pytest.mark.parametrize(
    "raw_path",
    [
        "..%5csecret.txt",
        "safe/..\\..\\secret.txt",
        "%2e%2e/%2e%2e/secret.txt",
        "C:%5cWindows%5cwin.ini",
        "//server/share/secret.txt",
        "CON",
        "AUX.txt",
        "file.txt::$DATA",
        "%00secret.txt",
    ],
)
def test_resolver_rejects_cross_platform_escape(tmp_path: Path, raw_path: str) -> None:
    assert _resolve_static_path(tmp_path, raw_path) is None


def test_resolver_allows_file_inside_root(tmp_path: Path) -> None:
    asset = tmp_path / "assets" / "app.js"
    asset.parent.mkdir()
    asset.write_text("safe", encoding="utf-8")
    assert _resolve_static_path(tmp_path, "assets/app.js") == asset.resolve()


def test_resolver_returns_none_for_missing_target(tmp_path: Path) -> None:
    assert _resolve_static_path(tmp_path, "assets/missing.js") is None


def test_resolver_allows_existing_directory_and_root(tmp_path: Path) -> None:
    assets = tmp_path / "assets"
    assets.mkdir()

    assert _resolve_static_path(tmp_path, "assets/") == assets.resolve()
    assert _resolve_static_path(tmp_path, "/") == tmp_path.resolve()


def test_resolver_rejects_symlink_escape(tmp_path: Path) -> None:
    root = tmp_path / "root"
    outside = tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "secret.txt").write_text("secret", encoding="utf-8")
    (root / "escape").symlink_to(outside, target_is_directory=True)
    assert _resolve_static_path(root, "escape/secret.txt") is None


def test_resolver_rejects_symlink_resolving_inside_root(tmp_path: Path) -> None:
    root = tmp_path / "root"
    target = root / "target"
    target.mkdir(parents=True)
    (target / "app.js").write_text("safe", encoding="utf-8")
    (root / "alias").symlink_to(target, target_is_directory=True)

    assert _resolve_static_path(root, "alias/app.js") is None


@pytest.mark.parametrize(
    "raw_path",
    [
        "COM¹",
        "COM².txt",
        "nested/lpt³.log",
        "COM¹.",
        "LPT² ",
        "COM%C2%B3.log",
    ],
)
def test_resolver_rejects_windows_superscript_device_alias(
    tmp_path: Path,
    raw_path: str,
) -> None:
    candidate = tmp_path / urllib.parse.unquote(raw_path)
    candidate.parent.mkdir(parents=True, exist_ok=True)
    candidate.write_text("must not be served", encoding="utf-8")

    assert _resolve_static_path(tmp_path, raw_path) is None


@pytest.mark.parametrize(
    "raw_path",
    [
        "CON .txt",
        "con  .TXT",
        "COM1 .log",
        "com9  .LOG",
        "COM¹ .txt",
        "com³  .TXT",
        "LPT² .dat",
        "lpt1  .DAT",
        "nested/Con .cfg",
        "CON .txt ",
        "LPT² .dat.",
    ],
)
def test_static_path_syntax_rejects_space_padded_windows_device_basename(
    raw_path: str,
) -> None:
    assert _static_path_parts(raw_path) is None


@pytest.mark.parametrize(
    "raw_path",
    [
        "CONSOLE .txt",
        "XCON .txt",
        "COM10 .log",
        "LPT20 .dat",
        "myCOM1 .txt",
        "COM¹file .txt",
        "nested/not-CON .txt",
    ],
)
def test_static_path_syntax_allows_ordinary_device_name_substrings(raw_path: str) -> None:
    assert _static_path_parts(raw_path) is not None


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
@pytest.mark.parametrize(
    "raw_path",
    [
        "//server/share/secret.txt",
        "/%2fserver/share/secret.txt",
        "/%5cserver/share/secret.txt",
        "/preview//server/share/secret.txt",
        "/preview/%2fserver/share/secret.txt",
        "/preview/%5cserver/share/secret.txt",
        "/preview/file://server/share/secret.txt",
        "/preview/C:%5cWindows%5cwin.ini",
        "/preview/%00secret.txt",
        "/preview/file.txt::$DATA",
        "/preview/CON",
        "/preview/%2e%2e/secret.txt",
    ],
)
def test_handlers_reject_unsafe_raw_path_before_route_prefix_stripping(
    tmp_path: Path,
    implementation: str,
    raw_path: str,
) -> None:
    static_root = tmp_path / "static"
    target = static_root / "server" / "share" / "secret.txt"
    target.parent.mkdir(parents=True)
    target.write_text("must not be served", encoding="utf-8")
    handler = _build_handler(
        implementation,
        {"/preview": str(static_root), "/": str(static_root)},
        tmp_path / "unconfigured-default",
    )

    assert handler.translate_path(raw_path) == _rejected_path(static_root)


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
def test_handlers_reject_unmapped_path_under_configured_root(
    tmp_path: Path,
    implementation: str,
) -> None:
    static_root = tmp_path / "static"
    static_root.mkdir()
    unconfigured_default = tmp_path / "unconfigured-default"
    unconfigured_default.mkdir()
    handler = _build_handler(
        implementation,
        {"/preview": str(static_root)},
        unconfigured_default,
    )

    translated = handler.translate_path("/unmapped/app.js")

    assert translated == _rejected_path(static_root)
    assert not Path(translated).exists()


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
@pytest.mark.parametrize("collision_kind", ["file", "directory"])
def test_http_handlers_reject_route_prefix_unc_with_sentinel_collision(
    tmp_path: Path,
    implementation: str,
    collision_kind: str,
) -> None:
    static_root = tmp_path / "static"
    secret = static_root / "server" / "share" / "secret.txt"
    secret.parent.mkdir(parents=True)
    secret.write_text("secret must not be served", encoding="utf-8")
    _create_sentinel_collision(static_root, collision_kind)
    handler_class = _build_handler_class(
        implementation,
        {"/preview": str(static_root), "/": str(static_root)},
    )

    with _running_http_server(handler_class) as address:
        status, body, _ = _http_get(address, "/preview//server/share/secret.txt")

    assert status == HTTPStatus.NOT_FOUND
    assert b"must not be served" not in body


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
@pytest.mark.parametrize("collision_kind", ["file", "directory"])
def test_http_handlers_reject_unmapped_path_with_sentinel_collision(
    tmp_path: Path,
    implementation: str,
    collision_kind: str,
) -> None:
    static_root = tmp_path / "static"
    static_root.mkdir()
    _create_sentinel_collision(static_root, collision_kind)
    handler_class = _build_handler_class(
        implementation,
        {"/preview": str(static_root)},
    )

    with _running_http_server(handler_class) as address:
        status, body, _ = _http_get(address, "/unmapped/app.js")

    assert status == HTTPStatus.NOT_FOUND
    assert b"must not be served" not in body


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
def test_http_handlers_serve_safe_file(
    tmp_path: Path,
    implementation: str,
) -> None:
    static_root = tmp_path / "static"
    static_root.mkdir()
    (static_root / "app.js").write_text("safe asset", encoding="utf-8")
    handler_class = _build_handler_class(
        implementation,
        {"/preview": str(static_root)},
    )

    with _running_http_server(handler_class) as address:
        status, body, _ = _http_get(address, "/preview/app.js")

    assert status == HTTPStatus.OK
    assert body == b"safe asset"


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
def test_http_handlers_redirect_and_serve_safe_directory_index(
    tmp_path: Path,
    implementation: str,
) -> None:
    static_root = tmp_path / "static"
    docs = static_root / "docs"
    docs.mkdir(parents=True)
    (docs / "index.html").write_text("safe index", encoding="utf-8")
    handler_class = _build_handler_class(
        implementation,
        {"/preview": str(static_root)},
    )

    with _running_http_server(handler_class) as address:
        redirect_status, _, location = _http_get(address, "/preview/docs")
        index_status, index_body, _ = _http_get(address, "/preview/docs/")

    assert redirect_status == HTTPStatus.MOVED_PERMANENTLY
    assert location == "/preview/docs/"
    assert index_status == HTTPStatus.OK
    assert index_body == b"safe index"


@pytest.mark.parametrize("implementation", ["factory", "legacy"])
@pytest.mark.parametrize("index_name", ["index.html", "index.htm"])
def test_http_handlers_reject_symlinked_directory_index_escape(
    tmp_path: Path,
    implementation: str,
    index_name: str,
) -> None:
    static_root = tmp_path / "static"
    docs = static_root / "docs"
    docs.mkdir(parents=True)
    outside_index = tmp_path / "outside-index.html"
    outside_index.write_text("secret index must not be served", encoding="utf-8")
    (docs / index_name).symlink_to(outside_index)
    handler_class = _build_handler_class(
        implementation,
        {"/preview": str(static_root)},
    )

    with _running_http_server(handler_class) as address:
        status, body, _ = _http_get(address, "/preview/docs/")

    assert status == HTTPStatus.NOT_FOUND
    assert b"secret index must not be served" not in body


def test_http_server_defaults_to_loopback() -> None:
    assert signature(run_http_server).parameters["host"].default == "127.0.0.1"


def test_http_listener_uses_threaded_requests_with_clean_close_semantics() -> None:
    server_class = _make_threaded_http_server_class()

    assert issubclass(server_class, socketserver.ThreadingMixIn)
    assert server_class.allow_reuse_address is True
    assert server_class.daemon_threads is True
    assert server_class.block_on_close is True


@pytest.mark.parametrize("host", ["", " \t "])
def test_http_server_rejects_blank_bind_before_opening_socket(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    host: str,
) -> None:
    bind_attempts: list[tuple[str, int]] = []
    monkeypatch.setattr(
        models.socketserver,
        "TCPServer",
        _make_capturing_tcp_server(bind_attempts),
    )

    with pytest.raises(ValueError, match="HTTP bind host"):
        run_http_server(8080, str(tmp_path), host)

    assert bind_attempts == []


def test_http_server_passes_explicit_wildcard_host_to_bind(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bind_attempts: list[tuple[str, int]] = []
    monkeypatch.setattr(
        models.socketserver,
        "TCPServer",
        _make_capturing_tcp_server(bind_attempts),
    )

    run_http_server(4321, str(tmp_path), "0.0.0.0")

    assert bind_attempts == [("0.0.0.0", 4321)]


def test_http_server_wraps_listener_with_supplied_ssl_context(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_socket = object()
    wrapped_socket = object()
    wrap_calls: list[tuple[object, bool]] = []

    class CapturingTCPServer:
        def __init__(self, server_address, handler_class):
            self.socket = original_socket

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def serve_forever(self):
            pass

    class FakeSSLContext:
        def wrap_socket(self, socket, *, server_side):
            wrap_calls.append((socket, server_side))
            return wrapped_socket

    monkeypatch.setattr(models.socketserver, "TCPServer", CapturingTCPServer)

    run_http_server(
        4321,
        str(tmp_path),
        "127.0.0.1",
        ssl_context=FakeSSLContext(),
    )

    assert wrap_calls == [(original_socket, True)]


@pytest.mark.parametrize(
    ("tls_cert", "tls_key"),
    [("cert.pem", None), (None, "key.pem")],
)
def test_vj_server_rejects_incomplete_tls_pair(tls_cert, tls_key) -> None:
    with pytest.raises(ValueError, match="TLS certificate and key"):
        VJServer(tls_cert=tls_cert, tls_key=tls_key)


def test_vj_server_uses_explicit_project_root(tmp_path: Path) -> None:
    server = VJServer(project_root=tmp_path)

    assert server.project_root == tmp_path.resolve()


def test_modern_cli_propagates_secure_listener_options(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    auth_file = tmp_path / "auth.json"
    auth_file.write_text('{"djs": {}, "vj_operators": {}}', encoding="utf-8")
    cert_file = tmp_path / "tls.crt"
    key_file = tmp_path / "tls.key"
    cert_file.touch()
    key_file.touch()
    captured: dict = {}

    class FakeVJServer:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def stop(self):
            pass

    def discard_coroutine(coroutine):
        coroutine.close()

    monkeypatch.setattr(vj_server_module, "VJServer", FakeVJServer)
    monkeypatch.setattr(cli_module.asyncio, "run", discard_coroutine)
    monkeypatch.setattr(cli_module.signal, "signal", lambda *args: None)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "audioviz-vj",
            "--auth-file",
            str(auth_file),
            "--http-port",
            "18443",
            "--project-root",
            str(tmp_path),
            "--tls-cert",
            str(cert_file),
            "--tls-key",
            str(key_file),
        ],
    )

    assert modern_cli_main() == 0
    assert captured["http_port"] == 18443
    assert captured["project_root"] == tmp_path
    assert captured["tls_cert"] == cert_file
    assert captured["tls_key"] == key_file


@pytest.mark.asyncio
async def test_vj_server_passes_tls_context_to_browser_listener(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ssl_context = object()
    listener_calls: list[dict] = []

    class FakeWebSocketServer:
        def close(self):
            pass

        async def wait_closed(self):
            pass

    async def capture_listener(*args, **kwargs):
        listener_calls.append(kwargs)
        return FakeWebSocketServer()

    async def no_op():
        pass

    server = VJServer(http_port=0, metrics_port=None, show_spectrograph=False)
    server.server_ssl_context = ssl_context
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = no_op
    monkeypatch.setattr(vj_server_module, "ws_serve", capture_listener)

    await server.run()

    assert listener_calls[0].get("ssl") is None
    assert listener_calls[1]["ssl"] is ssl_context


def test_http_server_does_not_serve_project_files_when_ui_assets_are_missing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    project_root = tmp_path / "project"
    project_root.mkdir()
    (project_root / ".env").write_text("secret must not be served", encoding="utf-8")
    bind_attempts: list[tuple[str, int]] = []
    handler_classes: list[type[http.server.SimpleHTTPRequestHandler]] = []

    with monkeypatch.context() as patch:
        patch.setattr(
            models.socketserver,
            "TCPServer",
            _make_capturing_tcp_server(bind_attempts, handler_classes),
        )
        run_http_server(4321, str(project_root))

    assert bind_attempts == [("127.0.0.1", 4321)]
    assert len(handler_classes) == 1
    with _running_http_server(handler_classes[0]) as address:
        admin_status, admin_body, _ = _http_get(address, "/.env")
        preview_status, preview_body, _ = _http_get(address, "/preview/.env")

    assert admin_status == HTTPStatus.NOT_FOUND
    assert preview_status == HTTPStatus.NOT_FOUND
    assert b"secret must not be served" not in admin_body
    assert b"secret must not be served" not in preview_body


@pytest.mark.parametrize("host", ["", " \t "])
def test_vj_server_rejects_blank_http_host(host: str) -> None:
    with pytest.raises(ValueError, match="HTTP bind host"):
        VJServer(http_host=host)


@pytest.mark.parametrize("host", ["", " \t "])
def test_modern_cli_rejects_blank_http_host_argument(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    host: str,
) -> None:
    monkeypatch.delenv("HTTP_HOST", raising=False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "audioviz-vj",
            "--http-host",
            host,
            "--auth-file",
            str(tmp_path / "missing-auth.json"),
        ],
    )

    with pytest.raises(SystemExit) as exc_info:
        modern_cli_main()

    assert exc_info.value.code == 2


@pytest.mark.parametrize("host", ["", " \t "])
def test_modern_cli_rejects_blank_http_host_from_environment(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    host: str,
) -> None:
    monkeypatch.setenv("HTTP_HOST", host)
    monkeypatch.setattr(
        sys,
        "argv",
        ["audioviz-vj", "--auth-file", str(tmp_path / "missing-auth.json")],
    )

    with pytest.raises(SystemExit) as exc_info:
        modern_cli_main()

    assert exc_info.value.code == 2


@pytest.mark.parametrize("host", ["", " \t "])
def test_legacy_cli_rejects_blank_http_host_argument(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    host: str,
) -> None:
    monkeypatch.delenv("HTTP_HOST", raising=False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "vj_server.py",
            "--http-host",
            host,
            "--config",
            str(tmp_path / "missing-auth.json"),
        ],
    )

    with pytest.raises(SystemExit) as exc_info:
        asyncio.run(legacy_main())

    assert exc_info.value.code == 2


@pytest.mark.parametrize("host", ["", " \t "])
def test_legacy_cli_rejects_blank_http_host_from_environment(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    host: str,
) -> None:
    monkeypatch.setenv("HTTP_HOST", host)
    monkeypatch.setattr(
        sys,
        "argv",
        ["vj_server.py", "--config", str(tmp_path / "missing-auth.json")],
    )

    with pytest.raises(SystemExit) as exc_info:
        asyncio.run(legacy_main())

    assert exc_info.value.code == 2
