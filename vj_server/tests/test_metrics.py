"""Tests for the metrics HTTP endpoint."""

import asyncio
import time
from unittest.mock import Mock

import pytest

from vj_server.metrics import start_metrics_server
from vj_server.pipeline_timing import PipelineTimingMetrics

METRICS_PORT = 9099
METRICS_HOST = "127.0.0.1"


@pytest.fixture
async def mock_server():
    """Create a mock VJServer with required metric attributes."""
    server = Mock()
    server._start_time = time.time() - 3600  # 1 hour uptime
    server._djs = {"dj1": Mock(), "dj2": Mock()}
    server._broadcast_clients = {Mock(), Mock(), Mock()}
    server._pattern_name = "spectrum"
    server._frames_processed = 42000
    server._pipeline_timing = PipelineTimingMetrics()
    server._pattern_changes = 5
    server._dj_connects = 10

    active_dj = Mock()
    active_dj.dj_name = "TestDJ"
    active_dj.bpm = 128.5
    server._get_active_dj = Mock(return_value=active_dj)

    mock_viz_client = Mock()
    mock_viz_client.connected = True
    server.viz_client = mock_viz_client

    return server


@pytest.fixture
async def metrics_server(mock_server):
    """Start and yield a metrics server, then clean up."""
    srv = await start_metrics_server(mock_server, METRICS_PORT, METRICS_HOST)
    yield srv
    srv.close()
    await srv.wait_closed()


async def _http_get(path: str) -> str:
    """Send a raw HTTP GET and return the full response as a string."""
    reader, writer = await asyncio.open_connection(METRICS_HOST, METRICS_PORT)
    writer.write(f"GET {path} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode())
    await writer.drain()
    response = await reader.read()
    writer.close()
    await writer.wait_closed()
    return response.decode("utf-8")


@pytest.mark.asyncio
async def test_health_returns_200_ok(metrics_server):
    response = await _http_get("/health")
    assert "200 OK" in response


@pytest.mark.asyncio
async def test_health_contains_status_ok(metrics_server):
    response = await _http_get("/health")
    assert '"status":"ok"' in response


@pytest.mark.asyncio
async def test_metrics_returns_200_ok(metrics_server):
    response = await _http_get("/metrics")
    assert "200 OK" in response


@pytest.mark.asyncio
async def test_metrics_exposes_observed_timing_without_fabricating_missing_stages(
    metrics_server, mock_server
):
    mock_server._pipeline_timing.observe("server_handler_ms", 2.5)
    response = await _http_get("/metrics")
    assert 'mcav_pipeline_window_ms{stage="server_handler_ms",statistic="p95"} 2.500000' in response
    assert 'stage="dj_analysis_ms"' not in response


@pytest.mark.asyncio
async def test_metrics_contains_uptime(metrics_server):
    response = await _http_get("/metrics")
    assert "mcav_uptime_seconds" in response


@pytest.mark.asyncio
async def test_unknown_path_returns_404(metrics_server):
    response = await _http_get("/notfound")
    assert "404 Not Found" in response
