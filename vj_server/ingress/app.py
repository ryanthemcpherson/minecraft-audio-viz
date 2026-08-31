"""aiohttp application and lifecycle for the unified public ingress."""

from __future__ import annotations

import asyncio
import ssl
import weakref
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

from aiohttp import web
from aiohttp.web_protocol import RequestHandler
from aiohttp.web_server import Server

from vj_server.ingress.limits import IngressLimits
from vj_server.ingress.security import (
    ConnectionLimiter,
    require_tls_policy,
    security_headers_middleware,
)
from vj_server.ingress.static import static_response


class _ConnectionReservation:
    def __init__(self, pool: _ConnectionPool) -> None:
        self._pool = pool
        self._released = False

    def release(self) -> None:
        if self._released:
            return
        self._released = True
        self._pool.release()


class _ConnectionPool:
    def __init__(self, capacity: int) -> None:
        self._capacity = capacity
        self._active = 0

    def reserve(self) -> _ConnectionReservation | None:
        if self._active >= self._capacity:
            return None
        self._active += 1
        return _ConnectionReservation(self)

    def release(self) -> None:
        if self._active <= 0:
            raise RuntimeError("ingress connection reservation underflow")
        self._active -= 1


class _ReservedProtocol(asyncio.Protocol):
    """Own one pre-handshake reservation and forward to aiohttp."""

    def __init__(
        self,
        handler: RequestHandler,
        reservation: _ConnectionReservation,
    ) -> None:
        self._handler = handler
        self._finalizer = weakref.finalize(self, reservation.release)

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        try:
            self._handler.connection_made(transport)
        except BaseException:
            self._finalizer()
            transport.abort()
            raise

    def data_received(self, data: bytes) -> None:
        self._handler.data_received(data)

    def eof_received(self) -> bool | None:
        return self._handler.eof_received()

    def connection_lost(self, exc: BaseException | None) -> None:
        try:
            self._handler.connection_lost(exc)
        finally:
            self._finalizer()

    def pause_writing(self) -> None:
        self._handler.pause_writing()

    def resume_writing(self) -> None:
        self._handler.resume_writing()


class _BoundedServer(Server):
    """aiohttp protocol factory with admission before request parsing."""

    def __init__(
        self,
        handler: Any,
        *,
        request_factory: Any,
        max_connections: int,
        initial_header_timeout_seconds: float,
        loop: asyncio.AbstractEventLoop,
        **kwargs: Any,
    ) -> None:
        self._connection_pool = _ConnectionPool(max_connections)
        self._initial_header_timeout_seconds = initial_header_timeout_seconds
        self._initial_header_handles: dict[RequestHandler, asyncio.TimerHandle] = {}
        self._application_request_factory = request_factory
        super().__init__(
            handler,
            request_factory=self._bounded_request_factory,
            handler_cancellation=True,
            loop=loop,
            **kwargs,
        )

    def __call__(self) -> asyncio.Protocol:
        reservation = self._connection_pool.reserve()
        if reservation is None:
            raise ConnectionRefusedError("ingress connection capacity reached")
        try:
            handler = super().__call__()
        except BaseException:
            reservation.release()
            raise
        return _ReservedProtocol(handler, reservation)

    def connection_made(
        self,
        handler: RequestHandler,
        transport: asyncio.Transport,
    ) -> None:
        super().connection_made(handler, transport)
        self._initial_header_handles[handler] = self._loop.call_later(
            self._initial_header_timeout_seconds,
            self._expire_initial_header,
            handler,
        )

    def connection_lost(
        self,
        handler: RequestHandler,
        exc: BaseException | None = None,
    ) -> None:
        self._cancel_initial_header_timeout(handler)
        super().connection_lost(handler, exc)

    async def shutdown(self, timeout: float | None = None) -> None:
        for handle in self._initial_header_handles.values():
            handle.cancel()
        self._initial_header_handles.clear()
        await super().shutdown(timeout)

    def _bounded_request_factory(
        self,
        message: Any,
        payload: Any,
        protocol: RequestHandler,
        writer: Any,
        task: asyncio.Task[None],
    ) -> Any:
        self._cancel_initial_header_timeout(protocol)
        return self._application_request_factory(message, payload, protocol, writer, task)

    def _expire_initial_header(self, handler: RequestHandler) -> None:
        self._initial_header_handles.pop(handler, None)
        handler.force_close()

    def _cancel_initial_header_timeout(self, handler: RequestHandler) -> None:
        handle = self._initial_header_handles.pop(handler, None)
        if handle is not None:
            handle.cancel()


class _BoundedAppRunner(web.AppRunner):
    """AppRunner that pins MCAV's bounded aiohttp server protocol."""

    def __init__(self, app: web.Application, *, limits: IngressLimits) -> None:
        super().__init__(
            app,
            access_log=None,
            shutdown_timeout=5.0,
            keepalive_timeout=limits.keepalive_timeout_seconds,
            lingering_time=1.0,
            max_line_size=limits.max_request_line_bytes,
            max_field_size=limits.max_header_field_bytes,
            max_headers=limits.max_header_count,
            read_bufsize=16_384,
            auto_decompress=False,
        )
        self._ingress_limits = limits

    async def _make_server(self) -> Server:
        # aiohttp does not expose a public custom-Server hook. This mirrors the
        # pinned AppRunner implementation so admission can happen at TCP accept.
        loop = asyncio.get_running_loop()
        app = self.app
        app._set_loop(loop)
        app.on_startup.freeze()
        await app.startup()
        app.freeze()
        return _BoundedServer(
            app._handle,
            request_factory=app._make_request,
            max_connections=self._ingress_limits.max_connections,
            initial_header_timeout_seconds=(self._ingress_limits.initial_header_timeout_seconds),
            loop=loop,
            **self._kwargs,
        )


class _IngressSite(web.TCPSite):
    """TCPSite that exposes asyncio's TLS handshake deadline."""

    def __init__(
        self,
        runner: web.BaseRunner,
        host: str,
        port: int,
        *,
        ssl_context: ssl.SSLContext | None,
        backlog: int,
        tls_handshake_timeout_seconds: float,
    ) -> None:
        super().__init__(
            runner,
            host,
            port,
            ssl_context=ssl_context,
            backlog=backlog,
        )
        self._tls_handshake_timeout_seconds = tls_handshake_timeout_seconds

    async def start(self) -> None:
        await web.BaseSite.start(self)
        loop = asyncio.get_running_loop()
        server = self._runner.server
        if server is None:
            raise RuntimeError("ingress runner has no protocol server")
        tls_options: dict[str, Any] = {}
        if self._ssl_context is not None:
            tls_options = {
                "ssl_handshake_timeout": self._tls_handshake_timeout_seconds,
                "ssl_shutdown_timeout": 1.0,
            }
        self._server = await loop.create_server(
            server,
            self._host,
            self._port,
            ssl=self._ssl_context,
            backlog=self._backlog,
            reuse_address=self._reuse_address,
            reuse_port=self._reuse_port,
            **tls_options,
        )
        if self._server.sockets:
            self._bound_port = self._server.sockets[0].getsockname()[1]
        else:
            self._bound_port = self._port


def create_ingress_application(
    project_root: Path,
    *,
    limits: IngressLimits,
    health_provider: Callable[[], bool],
    extra_routes: Iterable[web.AbstractRouteDef] = (),
    secure_transport: bool = False,
) -> web.Application:
    root = project_root.absolute()
    admin_root = root / "admin_panel"
    preview_root = root / "preview_tool" / "frontend"
    asset_root = root / "assets"
    limiter = ConnectionLimiter(limits.max_connections)

    @web.middleware
    async def connection_limit(
        request: web.Request,
        handler: web.RequestHandler,
    ) -> web.StreamResponse:
        async with limiter.reserve() as accepted:
            if not accepted:
                return web.json_response({"status": "busy"}, status=503)
            return await handler(request)

    async def health(_request: web.Request) -> web.Response:
        if health_provider():
            return web.json_response({"status": "ok"})
        return web.json_response({"status": "starting"}, status=503)

    async def preview_redirect(_request: web.Request) -> web.Response:
        raise web.HTTPPermanentRedirect(location="/preview/")

    async def admin_asset(request: web.Request) -> web.Response:
        raw_tail = _raw_tail(request, "/") or "index.html"
        return await static_response(request, admin_root, raw_tail, limits)

    async def preview_asset(request: web.Request) -> web.Response:
        raw_tail = _raw_tail(request, "/preview/") or "index.html"
        return await static_response(request, preview_root, raw_tail, limits)

    async def shared_asset(request: web.Request) -> web.Response:
        raw_tail = _raw_tail(request, "/assets/")
        return await static_response(request, asset_root, raw_tail, limits)

    app = web.Application(
        client_max_size=limits.max_request_body_bytes,
        middlewares=[
            security_headers_middleware(secure_transport=secure_transport),
            connection_limit,
        ],
    )
    app.router.add_get("/healthz", health)
    app.router.add_get("/preview", preview_redirect)
    app.router.add_routes(list(extra_routes))
    app.router.add_get("/preview/{tail:.*}", preview_asset)
    app.router.add_get("/assets/{tail:.*}", shared_asset)
    app.router.add_get("/{tail:.*}", admin_asset)
    return app


class IngressServer:
    def __init__(
        self,
        project_root: Path,
        *,
        host: str,
        port: int,
        ssl_context: ssl.SSLContext | None,
        allow_insecure_loopback: bool = False,
        limits: IngressLimits | None = None,
        certificate_fingerprint: str | None = None,
    ) -> None:
        if not host or not host.strip():
            raise ValueError("ingress host is required")
        if port < 0 or port > 65_535:
            raise ValueError("ingress port is outside the supported range")
        self._project_root = project_root.absolute()
        self._host = host.strip()
        self._port = port
        self._ssl_context = ssl_context
        self._allow_insecure_loopback = allow_insecure_loopback
        self._limits = limits or IngressLimits()
        self._certificate_fingerprint = _fingerprint(certificate_fingerprint)
        self._healthy = False
        self._bound_address: tuple[str, int] | None = None
        self._runner: web.AppRunner | None = None
        self._site: _IngressSite | None = None
        self._lifecycle_lock = asyncio.Lock()
        self._application = create_ingress_application(
            self._project_root,
            limits=self._limits,
            health_provider=lambda: self._healthy,
            secure_transport=self._ssl_context is not None,
        )

    @property
    def application(self) -> web.Application:
        return self._application

    @property
    def healthy(self) -> bool:
        return self._healthy

    @property
    def bound_address(self) -> tuple[str, int] | None:
        return self._bound_address

    @property
    def certificate_fingerprint(self) -> str | None:
        return self._certificate_fingerprint

    async def start(self) -> None:
        async with self._lifecycle_lock:
            if self._runner is not None:
                raise RuntimeError("ingress is already started")
            require_tls_policy(
                self._host,
                self._ssl_context,
                allow_insecure_loopback=self._allow_insecure_loopback,
            )
            runner = _BoundedAppRunner(self._application, limits=self._limits)
            self._runner = runner
            try:
                await runner.setup()
                site = _IngressSite(
                    runner,
                    self._host,
                    self._port,
                    ssl_context=self._ssl_context,
                    backlog=min(self._limits.max_connections, 128),
                    tls_handshake_timeout_seconds=(self._limits.tls_handshake_timeout_seconds),
                )
                self._site = site
                await site.start()
                self._bound_address = _site_address(site)
                self._healthy = True
            except BaseException:
                try:
                    await runner.cleanup()
                except BaseException:
                    # Keep the runner reachable so stop() can retry cleanup.
                    raise
                else:
                    self._runner = None
                    self._site = None
                raise

    async def stop(self) -> None:
        async with self._lifecycle_lock:
            self._healthy = False
            runner = self._runner
            if runner is None:
                return
            await runner.cleanup()
            self._runner = None
            self._site = None
            self._bound_address = None


def _raw_tail(request: web.Request, prefix: str) -> str:
    raw_path = request.raw_path.split("?", 1)[0]
    if not raw_path.startswith(prefix):
        return ""
    return raw_path[len(prefix) :]


def _site_address(site: web.TCPSite) -> tuple[str, int]:
    server = getattr(site, "_server", None)
    sockets = getattr(server, "sockets", None)
    if not sockets:
        raise RuntimeError("ingress listener did not publish a bound socket")
    address = sockets[0].getsockname()
    if not isinstance(address, tuple) or len(address) < 2:
        raise RuntimeError("ingress listener returned an invalid address")
    return str(address[0]), int(address[1])


def _fingerprint(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.replace(":", "").lower()
    if len(normalized) != 64 or any(
        character not in "0123456789abcdef" for character in normalized
    ):
        raise ValueError("certificate fingerprint must be SHA-256 hex")
    return normalized
