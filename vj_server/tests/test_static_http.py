from inspect import signature
from pathlib import Path

import pytest

from vj_server.models import _resolve_static_path, run_http_server


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


def test_resolver_rejects_symlink_escape(tmp_path: Path) -> None:
    root = tmp_path / "root"
    outside = tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "secret.txt").write_text("secret", encoding="utf-8")
    (root / "escape").symlink_to(outside, target_is_directory=True)
    assert _resolve_static_path(root, "escape/secret.txt") is None


def test_http_server_defaults_to_loopback() -> None:
    assert signature(run_http_server).parameters["host"].default == "127.0.0.1"
