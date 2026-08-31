"""aiohttp application and lifecycle for the unified public ingress."""

from __future__ import annotations

import asyncio
import logging
import ssl
import weakref
from collections import deque
from collections.abc import AsyncIterator, Awaitable, Callable, Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from aiohttp import web
from aiohttp.web_protocol import RequestHandler
from aiohttp.web_server import Server

from vj_server.ingress.limits import IngressLimits, RouteLimits
from vj_server.ingress.security import (
    ConnectionLimiter,
    browser_origin_allowed,
    require_tls_policy,
    security_headers_middleware,
)
from vj_server.ingress.static import static_response
from vj_server.transport import (
    AiohttpPeer,
    PeerClosed,
    PeerProtocolError,
    WebSocketPeer,
)

PeerHandler = Callable[[WebSocketPeer], Awaitable[None]]
logger = logging.getLogger("vj_server.ingress")


@dataclass(frozen=True, slots=True)
class WebSocketHandlers:
    dj: PeerHandler
    admin: PeerHandler
    preview: PeerHandler


class _RoutePeer:
    def __init__(
        self,
        peer: AiohttpPeer,
        limits: RouteLimits,
        *,
        text_only: bool,
    ) -> None:
        self._peer = peer
        self._limits = limits
        self._text_only = text_only
        self._started = asyncio.get_running_loop().time()
        self._message_times: deque[float] = deque()

    @property
    def remote_address(self) -> tuple[str, int] | None:
        return self._peer.remote_address

    @property
    def request_headers(self) -> Mapping[str, str]:
        return self._peer.request_headers

    @property
    def closed(self) -> bool:
        return self._peer.closed

    @property
    def auth_attempt_limit(self) -> int:
        return self._limits.max_auth_attempts

    async def recv(self) -> str | bytes:
        loop = asyncio.get_running_loop()
        remaining_lifetime = self._limits.lifetime_seconds - (loop.time() - self._started)
        timeout = min(self._limits.idle_timeout_seconds, remaining_lifetime)
        if timeout <= 0:
            await self.close(1008, "Route lifetime exceeded")
            raise PeerClosed(1008)
        try:
            message = await asyncio.wait_for(self._peer.recv(), timeout=timeout)
        except asyncio.TimeoutError:
            await self.close(1008, "Route timeout")
            raise PeerClosed(1008) from None
        if self._text_only and isinstance(message, bytes):
            await self.close(1003, "Text messages required")
            raise PeerProtocolError("Binary message rejected by route policy")
        now = loop.time()
        while self._message_times and self._message_times[0] <= now - 1.0:
            self._message_times.popleft()
        if len(self._message_times) >= self._limits.messages_per_second:
            await self.close(1008, "Message rate exceeded")
            raise PeerProtocolError("WebSocket message rate exceeds route limit")
        self._message_times.append(now)
        return message

    async def send(self, message: str | bytes) -> None:
        await self._peer.send(message)

    async def close(self, code: int = 1000, reason: str = "") -> None:
        await self._peer.close(code, reason)

    def __aiter__(self) -> AsyncIterator[str | bytes]:
        return self._messages()

    async def _messages(self) -> AsyncIterator[str | bytes]:
        while True:
            try:
                yield await self.recv()
            except PeerClosed as error:
                if error.code in {1000, 1001}:
                    return
                raise


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


def _websocket_routes(
    handlers: WebSocketHandlers,
    limits: IngressLimits,
    *,
    secure_transport: bool,
    browser_origins: tuple[str, ...],
) -> list[web.AbstractRouteDef]:
    return [
        web.get(
            "/ws/dj",
            _websocket_route(
                "dj",
                handlers.dj,
                limits.dj,
                secure_transport=secure_transport,
                browser_origins=browser_origins,
                require_browser_origin=False,
            ),
        ),
        web.get(
            "/ws/admin",
            _websocket_route(
                "admin",
                handlers.admin,
                limits.admin,
                secure_transport=secure_transport,
                browser_origins=browser_origins,
                require_browser_origin=True,
            ),
        ),
        web.get(
            "/ws/preview",
            _websocket_route(
                "preview",
                handlers.preview,
                limits.preview,
                secure_transport=secure_transport,
                browser_origins=browser_origins,
                require_browser_origin=True,
            ),
        ),
    ]


def _websocket_route(
    route_name: str,
    handler: PeerHandler,
    route_limits: RouteLimits,
    *,
    secure_transport: bool,
    browser_origins: tuple[str, ...],
    require_browser_origin: bool,
) -> Callable[[web.Request], Awaitable[web.StreamResponse]]:
    limiter = ConnectionLimiter(route_limits.max_connections)

    async def route(request: web.Request) -> web.StreamResponse:
        if require_browser_origin and not browser_origin_allowed(
            request.headers.get("Origin"),
            request.host,
            secure_transport=secure_transport,
            allowed_origins=browser_origins,
        ):
            raise web.HTTPForbidden(text="WebSocket origin rejected")
        async with limiter.reserve() as accepted:
            if not accepted:
                raise web.HTTPServiceUnavailable(text="WebSocket route busy")
            websocket = web.WebSocketResponse(
                max_msg_size=route_limits.max_message_bytes,
                heartbeat=None,
                autoclose=True,
                autoping=True,
            )
            await websocket.prepare(request)
            peer = _RoutePeer(
                AiohttpPeer(
                    websocket,
                    request,
                    max_message_bytes=route_limits.max_message_bytes,
                ),
                route_limits,
                text_only=True,
            )
            try:
                await handler(peer)
            except (PeerClosed, PeerProtocolError):
                pass
            except Exception as error:
                logger.error(
                    "WebSocket route handler failed: route=%s error_type=%s",
                    route_name,
                    type(error).__name__,
                )
                if not peer.closed:
                    await peer.close(1011, "Route handler failed")
            finally:
                if not peer.closed:
                    await peer.close()
            return websocket

    route.__name__ = f"{route_name}_websocket"
    return route


def create_ingress_application(
    project_root: Path,
    *,
    limits: IngressLimits,
    health_provider: Callable[[], bool],
    extra_routes: Iterable[web.AbstractRouteDef] = (),
    secure_transport: bool = False,
    websocket_handlers: WebSocketHandlers | None = None,
    browser_origins: tuple[str, ...] = (),
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

    async def runtime_config(_request: web.Request) -> web.Response:
        return web.Response(
            text="window.__MCAV_LEGACY_WS_PORT__ = null;\n",
            content_type="application/javascript",
            headers={"Cache-Control": "no-store"},
        )

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
    app.router.add_get("/mcav-runtime-config.js", runtime_config)
    if websocket_handlers is not None:
        app.router.add_routes(
            _websocket_routes(
                websocket_handlers,
                limits,
                secure_transport=secure_transport,
                browser_origins=browser_origins,
            )
        )
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
        websocket_handlers: WebSocketHandlers | None = None,
        browser_origins: tuple[str, ...] = (),
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
            websocket_handlers=websocket_handlers,
            browser_origins=browser_origins,
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

    async def stop_accepting(self) -> None:
        async with self._lifecycle_lock:
            self._healthy = False
            site = self._site
            if site is None:
                return
            await site.stop()
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
