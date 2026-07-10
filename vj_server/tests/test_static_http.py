import asyncio
import http.server
import sys
from inspect import signature
from pathlib import Path

import pytest

import vj_server.models as models
from vj_server.models import (
    _REJECTED_STATIC_PATH,
    MultiDirectoryHandler,
    _make_directory_handler,
    _resolve_static_path,
    run_http_server,
)
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


def test_http_server_defaults_to_loopback() -> None:
    assert signature(run_http_server).parameters["host"].default == "127.0.0.1"


@pytest.mark.parametrize("host", ["", " \t "])
def test_http_server_rejects_blank_bind_before_opening_socket(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    host: str,
) -> None:
    class BindMustNotBeAttempted:
        def __init__(self, *args: object, **kwargs: object) -> None:
            raise AssertionError("blank host reached the socket bind")

    monkeypatch.setattr(models.socketserver, "TCPServer", BindMustNotBeAttempted)

    with pytest.raises(ValueError, match="HTTP bind host"):
        run_http_server(8080, str(tmp_path), host)


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
