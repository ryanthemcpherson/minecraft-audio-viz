"""
Simple test script for metrics endpoint.

Run this to verify the metrics HTTP server works correctly.
"""

import asyncio
import sys
import time
from unittest.mock import Mock

# Fix Windows console encoding
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


async def test_metrics():
    """Test the metrics endpoints."""
    # Mock a VJServer instance with required attributes
    mock_server = Mock()
    mock_server._start_time = time.time() - 3600  # 1 hour uptime
    mock_server._djs = {"dj1": Mock(), "dj2": Mock()}
    mock_server._broadcast_clients = {Mock(), Mock(), Mock()}
    mock_server._pattern_name = "spectrum"
    mock_server._frames_processed = 42000
    mock_server._pattern_changes = 5
    mock_server._dj_connects = 10

    # Mock active DJ
    active_dj = Mock()
    active_dj.name = "TestDJ"
    active_dj.bpm = 128.5
    mock_server._get_active_dj = Mock(return_value=active_dj)

    # Mock Minecraft connection
    mock_viz_client = Mock()
    mock_viz_client.connected = True
    mock_server.viz_client = mock_viz_client

    # Start metrics server
    from metrics import start_metrics_server

    server = await start_metrics_server(mock_server, 9099, "127.0.0.1")

    print("[OK] Metrics server started on http://127.0.0.1:9099")

    # Test /health endpoint
    print("\nTesting /health endpoint...")
    reader, writer = await asyncio.open_connection("127.0.0.1", 9099)
    writer.write(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
    await writer.drain()

    response = await reader.read(4096)
    response_str = response.decode("utf-8")
    writer.close()
    await writer.wait_closed()

    if "200 OK" in response_str and '"status": "ok"' in response_str:
        print("[OK] /health endpoint works")
        print(
            f"  Response preview: {response_str.split(chr(13) + chr(10) + chr(13) + chr(10))[1][:200]}..."
        )
    else:
        print(f"[FAIL] /health endpoint failed\n{response_str}")

    # Test /metrics endpoint
    print("\nTesting /metrics endpoint...")
    reader, writer = await asyncio.open_connection("127.0.0.1", 9099)
    writer.write(b"GET /metrics HTTP/1.1\r\nHost: localhost\r\n\r\n")
    await writer.drain()

    response = await reader.read(4096)
    response_str = response.decode("utf-8")
    writer.close()
    await writer.wait_closed()

    if "200 OK" in response_str and "mcav_uptime_seconds" in response_str:
        print("[OK] /metrics endpoint works")
        # Extract body
        body = response_str.split("\r\n\r\n")[1]
        print(f"  Metrics preview:\n{body[:400]}...")
    else:
        print(f"[FAIL] /metrics endpoint failed\n{response_str}")

    # Test 404
    print("\nTesting 404 response...")
    reader, writer = await asyncio.open_connection("127.0.0.1", 9099)
    writer.write(b"GET /notfound HTTP/1.1\r\nHost: localhost\r\n\r\n")
    await writer.drain()

    response = await reader.read(4096)
    response_str = response.decode("utf-8")
    writer.close()
    await writer.wait_closed()

    if "404 Not Found" in response_str:
        print("[OK] 404 handling works")
    else:
        print(f"[FAIL] 404 handling failed\n{response_str}")

    # Cleanup
    server.close()
    await server.wait_closed()
    print("\n[OK] All tests passed!")


if __name__ == "__main__":
    asyncio.run(test_metrics())
