from __future__ import annotations

import os
import unicodedata
from pathlib import Path

import pytest
import pytest_asyncio
from aiohttp.test_utils import TestClient, TestServer
from yarl import URL

from vj_server.ingress import static as static_module
from vj_server.ingress.app import create_ingress_application
from vj_server.ingress.limits import IngressLimits
from vj_server.ingress.static import resolve_static_path


@pytest_asyncio.fixture
async def ingress_client(tmp_path: Path) -> TestClient:
    admin = tmp_path / "admin_panel"
    preview = tmp_path / "preview_tool" / "frontend"
    assets = tmp_path / "assets"
    (admin / "css").mkdir(parents=True)
    preview.mkdir(parents=True)
    assets.mkdir()
    (admin / "index.html").write_text("<main>admin</main>", encoding="utf-8")
    (admin / "css" / "admin.css").write_text("body{}", encoding="utf-8")
    (preview / "index.html").write_text("<main>preview</main>", encoding="utf-8")
    (assets / "app.0123456789abcdef.js").write_text("export {};", encoding="utf-8")
    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(),
        health_provider=lambda: True,
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        yield client
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_html_has_noncache_security_headers(ingress_client: TestClient) -> None:
    response = await ingress_client.get("/")

    assert response.status == 200
    assert await response.text() == "<main>admin</main>"
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert "unsafe-eval" not in response.headers["Content-Security-Policy"]
    assert response.headers["X-Frame-Options"] == "DENY"
    assert "text/html" in response.headers["Content-Type"]


@pytest.mark.asyncio
async def test_preview_relative_assets_and_hashed_assets_use_fixed_roots(
    ingress_client: TestClient,
) -> None:
    preview = await ingress_client.get("/preview/")
    admin_css = await ingress_client.get("/css/admin.css")
    asset = await ingress_client.get("/assets/app.0123456789abcdef.js")

    assert preview.status == 200
    assert await preview.text() == "<main>preview</main>"
    assert admin_css.status == 200
    assert admin_css.headers["Cache-Control"] == "no-cache"
    assert asset.status == 200
    assert asset.headers["Cache-Control"] == "public,max-age=31536000,immutable"
    assert "javascript" in asset.headers["Content-Type"]


@pytest.mark.asyncio
async def test_conditional_request_head_and_ranges(ingress_client: TestClient) -> None:
    first = await ingress_client.get("/css/admin.css")
    etag = first.headers["ETag"]

    cached = await ingress_client.get("/css/admin.css", headers={"If-None-Match": etag})
    head = await ingress_client.head("/css/admin.css")
    ranged = await ingress_client.get("/css/admin.css", headers={"Range": "bytes=0-2"})

    assert cached.status == 304
    assert await cached.read() == b""
    assert head.status == 200
    assert await head.read() == b""
    assert ranged.status == 416


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "path",
    [
        "/missing.js",
        "/.secret.js",
        "/css/",
        "/css/%2e%2e/index.html",
        "/css/%252e%252e/index.html",
        "/css%2fadmin.css",
        "/css%5cadmin.css",
        "/C:%5cWindows%5cwin.ini",
        "/CON",
        "/file.js::$DATA",
        "/%00app.js",
    ],
)
async def test_unsafe_and_missing_http_paths_are_not_served(
    ingress_client: TestClient,
    path: str,
) -> None:
    response = await ingress_client.get(URL(path, encoded=True))

    assert response.status in {400, 404}


@pytest.mark.parametrize(
    "raw_path",
    [
        "",
        ".hidden.js",
        "safe//app.js",
        "safe/../app.js",
        "safe\\app.js",
        "C:/Windows/win.ini",
        "NUL.txt",
        "COM1",
        "file.js::$DATA",
        "%2fetc/passwd",
        "%252e%252e/secret.js",
        "bad\x00.js",
        "bad\x1f.js",
    ],
)
def test_static_resolver_rejects_ambiguous_cross_platform_paths(
    tmp_path: Path,
    raw_path: str,
) -> None:
    assert resolve_static_path(tmp_path, raw_path) is None


def test_static_resolver_rejects_non_nfc_and_case_collisions(tmp_path: Path) -> None:
    decomposed = "cafe\u0301.js"
    assert unicodedata.normalize("NFC", decomposed) != decomposed
    (tmp_path / unicodedata.normalize("NFC", decomposed)).write_text("safe", encoding="utf-8")
    assert resolve_static_path(tmp_path, decomposed) is None

    (tmp_path / "APP.js").write_text("one", encoding="utf-8")
    (tmp_path / "app.js").write_text("two", encoding="utf-8")
    assert resolve_static_path(tmp_path, "app.js") is None


def test_static_resolver_rejects_symlink_even_when_target_is_inside(tmp_path: Path) -> None:
    target = tmp_path / "target.js"
    target.write_text("safe", encoding="utf-8")
    link = tmp_path / "alias.js"
    try:
        link.symlink_to(target)
    except OSError as error:
        pytest.skip(f"symlinks unavailable: {error}")

    assert resolve_static_path(tmp_path, "alias.js") is None


@pytest.mark.asyncio
async def test_configured_static_root_symlink_is_never_trusted(tmp_path: Path) -> None:
    outside = tmp_path / "outside-admin"
    outside.mkdir()
    (outside / "index.html").write_text("outside", encoding="utf-8")
    (tmp_path / "preview_tool" / "frontend").mkdir(parents=True)
    (tmp_path / "assets").mkdir()
    try:
        (tmp_path / "admin_panel").symlink_to(outside, target_is_directory=True)
    except OSError as error:
        pytest.skip(f"directory symlinks unavailable: {error}")

    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(),
        health_provider=lambda: True,
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        response = await client.get("/")
    finally:
        await client.close()

    assert response.status == 404


@pytest.mark.asyncio
async def test_disallowed_mime_and_oversized_asset_are_rejected(tmp_path: Path) -> None:
    admin = tmp_path / "admin_panel"
    (tmp_path / "preview_tool" / "frontend").mkdir(parents=True)
    (tmp_path / "assets").mkdir()
    admin.mkdir()
    (admin / "index.html").write_text("ok", encoding="utf-8")
    (admin / "secret.txt").write_text("no", encoding="utf-8")
    (admin / "large.js").write_bytes(os.urandom(17))
    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(max_static_asset_bytes=16),
        health_provider=lambda: True,
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        disallowed = await client.get("/secret.txt")
        oversized = await client.get("/large.js")
    finally:
        await client.close()

    assert disallowed.status == 404
    assert oversized.status == 413


@pytest.mark.asyncio
async def test_opened_asset_is_revalidated_against_its_root(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    admin = tmp_path / "admin_panel"
    (tmp_path / "preview_tool" / "frontend").mkdir(parents=True)
    (tmp_path / "assets").mkdir()
    admin.mkdir()
    (admin / "index.html").write_text("ok", encoding="utf-8")
    outside = tmp_path / "outside.js"
    outside.write_text("secret", encoding="utf-8")
    original_resolver = static_module.resolve_static_path

    def swapped_resolver(root: Path, raw_path: str) -> Path | None:
        if raw_path == "swap.js":
            return outside
        return original_resolver(root, raw_path)

    monkeypatch.setattr(static_module, "resolve_static_path", swapped_resolver)
    app = create_ingress_application(
        tmp_path,
        limits=IngressLimits(),
        health_provider=lambda: True,
    )
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        response = await client.get("/swap.js")
    finally:
        await client.close()

    assert response.status == 404


@pytest.mark.asyncio
async def test_head_does_not_read_asset_body(
    ingress_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_read(*_args: object, **_kwargs: object) -> bytes:
        raise AssertionError("HEAD must not read the asset body")

    monkeypatch.setattr(static_module, "_read_asset_body", fail_read, raising=False)

    response = await ingress_client.head("/css/admin.css")

    assert response.status == 200
    assert await response.read() == b""
    assert response.headers["Content-Length"] == str(len(b"body{}"))


@pytest.mark.skipif(os.name != "nt", reason="Windows CreateFileW regression")
def test_windows_native_directory_handle_serves_contained_asset(tmp_path: Path) -> None:
    root = tmp_path / "admin_panel"
    root.mkdir()
    asset_path = root / "app.js"
    asset_path.write_bytes(b"safe")
    resolved = resolve_static_path(root, "app.js")
    assert resolved is not None

    asset = static_module._open_asset(root, resolved, 16, True, None)

    assert asset.size == 4
    assert asset.body == b"safe"
