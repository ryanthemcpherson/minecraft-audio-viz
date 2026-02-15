"""
Health and metrics HTTP endpoint for VJ server monitoring.

Provides lightweight HTTP endpoints for production monitoring:
- GET /health - JSON health check
- GET /metrics - Prometheus-compatible text format metrics
"""

import asyncio
import logging
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from vj_server.vj_server import VJServer

logger = logging.getLogger(__name__)


async def handle_http_request(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    server: "VJServer",
) -> None:
    """Handle a single HTTP request."""
    try:
        # Read request line
        request_line = await reader.readline()
        if not request_line:
            return

        request_line = request_line.decode("utf-8").strip()
        parts = request_line.split()
        if len(parts) < 2:
            return

        method, path = parts[0], parts[1]

        # Read headers (and discard them - we don't need them for these endpoints)
        while True:
            line = await reader.readline()
            if not line or line == b"\r\n":
                break

        # Route request
        if method == "GET" and path == "/health":
            await _handle_health(writer, server)
        elif method == "GET" and path == "/metrics":
            await _handle_metrics(writer, server)
        else:
            # 404 Not Found
            response = (
                "HTTP/1.1 404 Not Found\r\n"
                "Content-Type: text/plain\r\n"
                "Content-Length: 9\r\n"
                "\r\n"
                "Not Found"
            )
            writer.write(response.encode("utf-8"))

    except Exception as e:
        logger.error(f"Error handling metrics request: {e}")
    finally:
        try:
            await writer.drain()
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def _handle_health(writer: asyncio.StreamWriter, server: "VJServer") -> None:
    """Handle /health endpoint - returns JSON status."""
    import json

    uptime = time.time() - server._start_time
    active_dj = server._get_active_dj()

    health_data = {
        "status": "ok",
        "uptime_seconds": round(uptime, 2),
        "connected_djs": len(server._djs),
        "connected_browsers": len(server._broadcast_clients),
        "active_pattern": server._pattern_name,
        "active_dj": active_dj.name if active_dj else None,
        "minecraft_connected": (server.viz_client is not None and server.viz_client.connected),
    }

    body = json.dumps(health_data, indent=2)
    response = (
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\n"
        "\r\n"
        f"{body}"
    )
    writer.write(response.encode("utf-8"))


async def _handle_metrics(writer: asyncio.StreamWriter, server: "VJServer") -> None:
    """Handle /metrics endpoint - returns Prometheus text format."""
    uptime = time.time() - server._start_time
    active_dj = server._get_active_dj()
    current_bpm = active_dj.bpm if active_dj and active_dj.bpm > 0 else 0.0

    # Build Prometheus text format
    # Format: metric_name{label="value"} value
    lines = [
        "# HELP mcav_uptime_seconds Server uptime in seconds",
        "# TYPE mcav_uptime_seconds gauge",
        f"mcav_uptime_seconds {uptime:.2f}",
        "",
        "# HELP mcav_connected_djs Number of currently connected DJs",
        "# TYPE mcav_connected_djs gauge",
        f"mcav_connected_djs {len(server._djs)}",
        "",
        "# HELP mcav_connected_browsers Number of currently connected browser clients",
        "# TYPE mcav_connected_browsers gauge",
        f"mcav_connected_browsers {len(server._broadcast_clients)}",
        "",
        "# HELP mcav_frames_processed_total Total audio frames processed",
        "# TYPE mcav_frames_processed_total counter",
        f"mcav_frames_processed_total {server._frames_processed}",
        "",
        "# HELP mcav_pattern_changes_total Total pattern changes",
        "# TYPE mcav_pattern_changes_total counter",
        f"mcav_pattern_changes_total {server._pattern_changes}",
        "",
        "# HELP mcav_dj_connections_total Total DJ connections since start",
        "# TYPE mcav_dj_connections_total counter",
        f"mcav_dj_connections_total {server._dj_connects}",
        "",
        "# HELP mcav_current_bpm Current BPM from active DJ",
        "# TYPE mcav_current_bpm gauge",
        f"mcav_current_bpm {current_bpm:.1f}",
        "",
        "# HELP mcav_active_pattern Currently active visualization pattern",
        "# TYPE mcav_active_pattern gauge",
        f'mcav_active_pattern{{pattern="{server._pattern_name}"}} 1',
        "",
    ]

    body = "\n".join(lines)
    response = (
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/plain; version=0.0.4\r\n"
        f"Content-Length: {len(body)}\r\n"
        "\r\n"
        f"{body}"
    )
    writer.write(response.encode("utf-8"))


async def start_metrics_server(
    server: "VJServer",
    port: int,
    host: str = "0.0.0.0",
) -> asyncio.Server:
    """Start the metrics HTTP server.

    Args:
        server: VJServer instance to expose metrics for
        port: Port to listen on
        host: Host to bind to (default: 0.0.0.0)

    Returns:
        asyncio.Server instance
    """

    async def client_handler(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        await handle_http_request(reader, writer, server)

    metrics_server = await asyncio.start_server(client_handler, host, port)
    logger.info(f"Metrics server: http://localhost:{port}/health, /metrics")
    return metrics_server
