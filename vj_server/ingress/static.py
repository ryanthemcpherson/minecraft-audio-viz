"""Contained static-asset resolution and responses."""

from __future__ import annotations

import asyncio
import os
import re
import stat as stat_module
import unicodedata
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

from aiohttp import web

from vj_server.ingress.limits import IngressLimits

MIME_TYPES = {
    ".css": "text/css",
    ".html": "text/html",
    ".ico": "image/x-icon",
    ".jpeg": "image/jpeg",
    ".jpg": "image/jpeg",
    ".js": "text/javascript",
    ".json": "application/json",
    ".map": "application/json",
    ".mjs": "text/javascript",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".wasm": "application/wasm",
    ".woff2": "font/woff2",
}
WINDOWS_DEVICES = frozenset(
    {"CON", "PRN", "AUX", "NUL"}
    | {f"COM{number}" for number in range(1, 10)}
    | {f"LPT{number}" for number in range(1, 10)}
)
HASHED_ASSET = re.compile(r"(?:^|[.-])[0-9a-fA-F]{8,}(?:[.-]|$)")


class _AssetUnavailable(Exception):
    pass


class _AssetTooLarge(Exception):
    def __init__(self, actual_size: int) -> None:
        self.actual_size = actual_size


@dataclass(frozen=True, slots=True)
class _OpenedAsset:
    size: int
    modified_ns: int
    body: bytes | None


def resolve_static_path(root: Path, raw_path: str) -> Path | None:
    if not raw_path or len(raw_path) > 2_048:
        return None
    lowered = raw_path.lower()
    if "%2f" in lowered or "%5c" in lowered:
        return None
    try:
        decoded = urllib.parse.unquote(raw_path, encoding="utf-8", errors="strict")
    except (UnicodeDecodeError, ValueError):
        return None
    if "%" in decoded or unicodedata.normalize("NFC", decoded) != decoded:
        return None
    parts = decoded.split("/")
    if any(not _safe_segment(part) for part in parts):
        return None
    try:
        if root.is_symlink() or not root.is_dir():
            return None
        resolved_root = root.resolve(strict=True)
        current = resolved_root
        for part in parts:
            if _case_collision(current, part):
                return None
            current = current / part
            if current.is_symlink() or not current.exists():
                return None
        if not current.is_file():
            return None
        resolved = current.resolve(strict=True)
        if not resolved.is_relative_to(resolved_root):
            return None
        if resolved.suffix.lower() not in MIME_TYPES:
            return None
        return resolved
    except OSError:
        return None


async def static_response(
    request: web.Request,
    root: Path,
    raw_path: str,
    limits: IngressLimits,
) -> web.Response:
    path = resolve_static_path(root, raw_path)
    if path is None:
        raise web.HTTPNotFound()
    try:
        asset = await asyncio.to_thread(
            _open_asset,
            root,
            path,
            limits.max_static_asset_bytes,
            request.method == "GET" and request.headers.get("Range") is None,
            request.headers.get("If-None-Match"),
        )
    except _AssetUnavailable:
        raise web.HTTPNotFound() from None
    except _AssetTooLarge as error:
        raise web.HTTPRequestEntityTooLarge(
            max_size=limits.max_static_asset_bytes,
            actual_size=error.actual_size,
        ) from None
    if request.headers.get("Range") is not None:
        raise web.HTTPRequestRangeNotSatisfiable(headers={"Content-Range": f"bytes */{asset.size}"})
    etag = f'"{asset.size:x}-{asset.modified_ns:x}"'
    cache_control = _cache_control(path)
    if request.headers.get("If-None-Match") == etag:
        return web.Response(status=304, headers={"ETag": etag, "Cache-Control": cache_control})
    headers = {
        "ETag": etag,
        "Cache-Control": cache_control,
        "Accept-Ranges": "none",
    }
    if request.method == "HEAD":
        headers["Content-Length"] = str(asset.size)
    return web.Response(
        body=asset.body,
        content_type=MIME_TYPES[path.suffix.lower()],
        headers=headers,
    )


def _open_asset(
    root: Path,
    path: Path,
    max_bytes: int,
    read_body: bool,
    if_none_match: str | None,
) -> _OpenedAsset:
    if os.name == "nt":
        return _open_asset_windows(root, path, max_bytes, read_body, if_none_match)
    return _open_asset_posix(root, path, max_bytes, read_body, if_none_match)


def _open_asset_posix(
    root: Path,
    path: Path,
    max_bytes: int,
    read_body: bool,
    if_none_match: str | None,
) -> _OpenedAsset:
    common_flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_CLOEXEC", 0)
    no_follow_flags = common_flags | getattr(os, "O_NOFOLLOW", 0)
    root_flags = no_follow_flags | getattr(os, "O_DIRECTORY", 0)
    try:
        root_descriptor = os.open(root, root_flags)
    except OSError:
        raise _AssetUnavailable from None
    try:
        opened_root = _opened_path(root_descriptor, root)
        if opened_root is None or not _same_path(opened_root, root.absolute()):
            raise _AssetUnavailable
        try:
            relative_path = path.relative_to(opened_root)
        except ValueError:
            raise _AssetUnavailable
        descriptor = _open_relative_asset(
            root_descriptor,
            opened_root,
            relative_path,
            no_follow_flags,
        )
        try:
            expected_path = opened_root / relative_path
            return _asset_from_descriptor(
                descriptor,
                opened_root,
                expected_path,
                max_bytes,
                read_body,
                if_none_match,
            )
        finally:
            os.close(descriptor)
    finally:
        os.close(root_descriptor)


def _open_relative_asset(
    root_descriptor: int,
    opened_root: Path,
    relative_path: Path,
    flags: int,
) -> int:
    try:
        return os.open(relative_path, flags, dir_fd=root_descriptor)
    except (NotImplementedError, TypeError):
        try:
            return os.open(opened_root / relative_path, flags)
        except OSError:
            raise _AssetUnavailable from None
    except OSError:
        raise _AssetUnavailable from None


def _open_asset_windows(
    root: Path,
    path: Path,
    max_bytes: int,
    read_body: bool,
    if_none_match: str | None,
) -> _OpenedAsset:
    import msvcrt

    root_handle = _windows_create_handle(root, directory=True)
    if root_handle is None:
        raise _AssetUnavailable
    try:
        if _windows_handle_is_reparse(root_handle):
            raise _AssetUnavailable
        opened_root = _windows_handle_path(root_handle)
        if opened_root is None or not _same_path(opened_root, root.absolute()):
            raise _AssetUnavailable
        try:
            relative_path = path.relative_to(opened_root)
        except ValueError:
            raise _AssetUnavailable
        expected_path = opened_root / relative_path
        asset_handle = _windows_create_handle(expected_path, directory=False)
        if asset_handle is None:
            raise _AssetUnavailable
        descriptor: int | None = None
        try:
            if _windows_handle_is_reparse(asset_handle):
                raise _AssetUnavailable
            current_root = _windows_handle_path(root_handle)
            if current_root is None or not _same_path(current_root, opened_root):
                raise _AssetUnavailable
            descriptor = msvcrt.open_osfhandle(
                asset_handle,
                os.O_RDONLY | getattr(os, "O_BINARY", 0),
            )
            asset_handle = None
            return _asset_from_descriptor(
                descriptor,
                opened_root,
                expected_path,
                max_bytes,
                read_body,
                if_none_match,
            )
        finally:
            if descriptor is not None:
                os.close(descriptor)
            elif asset_handle is not None:
                _windows_close_handle(asset_handle)
    finally:
        _windows_close_handle(root_handle)


def _asset_from_descriptor(
    descriptor: int,
    opened_root: Path,
    expected_path: Path,
    max_bytes: int,
    read_body: bool,
    if_none_match: str | None,
) -> _OpenedAsset:
    opened_path = _opened_path(descriptor, expected_path)
    if opened_path is None or not _same_path(opened_path, expected_path):
        raise _AssetUnavailable
    if not opened_path.is_relative_to(opened_root):
        raise _AssetUnavailable
    metadata = os.fstat(descriptor)
    if not stat_module.S_ISREG(metadata.st_mode):
        raise _AssetUnavailable
    if metadata.st_size > max_bytes:
        raise _AssetTooLarge(metadata.st_size)
    etag = f'"{metadata.st_size:x}-{metadata.st_mtime_ns:x}"'
    body = _read_asset_body(descriptor, max_bytes) if read_body and if_none_match != etag else None
    if body is not None and len(body) != metadata.st_size:
        raise _AssetUnavailable
    return _OpenedAsset(metadata.st_size, metadata.st_mtime_ns, body)


def _windows_create_handle(path: Path, *, directory: bool) -> int | None:
    import ctypes
    from ctypes import wintypes

    create_file = ctypes.windll.kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    desired_access = 0x80 if directory else 0x80000000
    flags = 0x00200000 | (0x02000000 if directory else 0)
    handle = create_file(
        str(path),
        desired_access,
        0x1 | 0x2 | 0x4,
        None,
        3,
        flags,
        None,
    )
    invalid_handle = ctypes.c_void_p(-1).value
    if handle == invalid_handle:
        return None
    return int(handle)


def _windows_handle_is_reparse(handle: int) -> bool:
    import ctypes
    from ctypes import wintypes

    class FileAttributeTagInfo(ctypes.Structure):
        _fields_ = [
            ("file_attributes", wintypes.DWORD),
            ("reparse_tag", wintypes.DWORD),
        ]

    information = FileAttributeTagInfo()
    get_information = ctypes.windll.kernel32.GetFileInformationByHandleEx
    get_information.argtypes = [
        wintypes.HANDLE,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
    ]
    get_information.restype = wintypes.BOOL
    succeeded = get_information(
        handle,
        9,
        ctypes.byref(information),
        ctypes.sizeof(information),
    )
    if not succeeded:
        raise _AssetUnavailable
    return bool(information.file_attributes & 0x400)


def _windows_close_handle(handle: int) -> None:
    import ctypes
    from ctypes import wintypes

    close_handle = ctypes.windll.kernel32.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    close_handle(handle)


def _windows_handle_path(handle: int) -> Path | None:
    import ctypes
    from ctypes import wintypes

    get_final_path = ctypes.windll.kernel32.GetFinalPathNameByHandleW
    get_final_path.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
    get_final_path.restype = wintypes.DWORD
    required = get_final_path(handle, None, 0, 0)
    if required == 0:
        return None
    buffer = ctypes.create_unicode_buffer(required + 1)
    if get_final_path(handle, buffer, len(buffer), 0) == 0:
        return None
    value = buffer.value
    if value.startswith("\\\\?\\UNC\\"):
        value = "\\\\" + value[8:]
    elif value.startswith("\\\\?\\"):
        value = value[4:]
    try:
        return Path(value).resolve(strict=True)
    except OSError:
        return None


def _read_asset_body(descriptor: int, max_bytes: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = os.read(descriptor, min(65_536, max_bytes + 1 - total))
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)
        total += len(chunk)
        if total > max_bytes:
            raise _AssetTooLarge(total)


def _opened_path(descriptor: int, expected: Path) -> Path | None:
    if os.name == "nt":
        return _windows_opened_path(descriptor)
    for descriptor_root in (Path("/proc/self/fd"), Path("/dev/fd")):
        if not descriptor_root.is_dir():
            continue
        try:
            return (descriptor_root / str(descriptor)).resolve(strict=True)
        except OSError:
            return None
    try:
        opened = os.fstat(descriptor)
        current = expected.stat(follow_symlinks=False)
    except OSError:
        return None
    if (opened.st_dev, opened.st_ino) != (current.st_dev, current.st_ino):
        return None
    try:
        return expected.resolve(strict=True)
    except OSError:
        return None


def _windows_opened_path(descriptor: int) -> Path | None:
    try:
        import msvcrt

        handle = msvcrt.get_osfhandle(descriptor)
        return _windows_handle_path(handle)
    except (OSError, ValueError):
        return None


def _same_path(left: Path, right: Path) -> bool:
    return os.path.normcase(str(left)) == os.path.normcase(str(right))


def _safe_segment(segment: str) -> bool:
    if (
        not segment
        or segment in {".", ".."}
        or segment.startswith(".")
        or segment.endswith((" ", "."))
        or ":" in segment
        or "\\" in segment
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in segment)
        or len(segment.encode("utf-8")) > 255
    ):
        return False
    device_stem = segment.split(".", 1)[0].upper()
    return device_stem not in WINDOWS_DEVICES


def _case_collision(parent: Path, requested: str) -> bool:
    matches = [
        entry.name for entry in parent.iterdir() if entry.name.casefold() == requested.casefold()
    ]
    return len(matches) != 1 or matches[0] != requested


def _cache_control(path: Path) -> str:
    if path.suffix.lower() == ".html":
        return "no-store"
    if HASHED_ASSET.search(path.name):
        return "public,max-age=31536000,immutable"
    return "no-cache"
