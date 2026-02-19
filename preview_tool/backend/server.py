"""
Browser Preview Tool - Backend Server
Serves the web interface and broadcasts audio visualization data via WebSocket.
"""

import asyncio
import json
import os
import sys
import math
import time
import argparse
import logging
from pathlib import Path
from typing import Set
import http.server
import socketserver
import threading

# Add parent for imports
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

try:
    import websockets
    from websockets.server import serve
except ImportError:
    print("Please install websockets: pip install websockets")
    sys.exit(1)

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
logger = logging.getLogger('preview_server')

# Connected browser clients
clients: Set = set()

# Current visualization state (5 bands for ultra-low-latency)
current_state = {
    "bands": [0.0] * 5,
    "amplitude": 0.0,
    "is_beat": False,
    "beat_intensity": 0.0,
    "frame": 0
}


async def broadcast(message: dict):
    """Broadcast message to all connected clients."""
    if clients:
        msg = json.dumps(message)
        await asyncio.gather(*[client.send(msg) for client in clients], return_exceptions=True)


async def handle_client(websocket):
    """Handle a browser WebSocket connection."""
    clients.add(websocket)
    logger.info(f"Browser connected. Total clients: {len(clients)}")

    try:
        # Send current state immediately
        await websocket.send(json.dumps({"type": "state", **current_state}))

        async for message in websocket:
            # Handle messages from browser (e.g., config changes)
            try:
                data = json.loads(message)
                if data.get("type") == "ping":
                    await websocket.send(json.dumps({"type": "pong"}))
            except json.JSONDecodeError:
                pass

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        clients.discard(websocket)
        logger.info(f"Browser disconnected. Total clients: {len(clients)}")


async def run_demo_mode():
    """Generate simulated audio data for demo/testing."""
    logger.info("Running in demo mode with simulated audio")

    t = 0
    beat_interval = 60.0 / 128  # 128 BPM
    last_beat = 0

    while True:
        current_state["frame"] += 1

        # Simulate frequency bands
        current_state["bands"] = [
            0.5 + 0.4 * math.sin(t * 0.8 + 0),
            0.4 + 0.35 * math.sin(t * 1.1 + 0.5),
            0.35 + 0.3 * math.sin(t * 1.5 + 1.0),
            0.3 + 0.25 * math.sin(t * 2.0 + 1.5),
            0.25 + 0.2 * math.sin(t * 2.8 + 2.0),
            0.2 + 0.15 * math.sin(t * 3.5 + 2.5),
        ]

        # Simulate amplitude
        current_state["amplitude"] = 0.4 + 0.3 * math.sin(t * 0.5)

        # Simulate beats
        current_time = t / 10  # Scale time
        if current_time - last_beat >= beat_interval:
            last_beat = current_time
            current_state["is_beat"] = True
            current_state["beat_intensity"] = 0.7 + 0.3 * abs(math.sin(t))
        else:
            current_state["is_beat"] = False
            current_state["beat_intensity"] = 0

        # Broadcast to clients
        await broadcast({"type": "audio", **current_state})

        t += 0.1
        await asyncio.sleep(0.016)  # ~60 FPS


async def connect_to_capture(host: str, port: int):
    """Connect to the audio capture agent and relay data."""
    logger.info(f"Connecting to audio capture at {host}:{port}")

    while True:
        try:
            async with websockets.connect(f"ws://{host}:{port}") as ws:
                logger.info("Connected to audio capture")

                async for message in ws:
                    try:
                        data = json.loads(message)
                        if "bands" in data:
                            current_state.update(data)
                            current_state["frame"] += 1
                            await broadcast({"type": "audio", **current_state})
                    except json.JSONDecodeError:
                        pass

        except Exception as e:
            logger.warning(f"Connection failed: {e}. Retrying in 2s...")
            await asyncio.sleep(2)


class MultiDirectoryHandler(http.server.SimpleHTTPRequestHandler):
    """HTTP handler that serves from multiple directories based on URL path."""

    # Map URL prefixes to directories
    directory_map = {}

    def translate_path(self, path):
        """Translate URL path to file system path."""
        # Remove query string and fragment
        path = path.split('?')[0].split('#')[0]

        # Check each directory mapping
        for url_prefix, fs_directory in self.directory_map.items():
            if path.startswith(url_prefix):
                # Remove the URL prefix and join with the filesystem directory
                relative_path = path[len(url_prefix):].lstrip('/')
                return os.path.join(fs_directory, relative_path)

        # Default to the first directory for root
        if '/' in self.directory_map:
            return os.path.join(self.directory_map['/'], path.lstrip('/'))

        return super().translate_path(path)

    def log_message(self, format, *args):
        """Suppress HTTP request logging."""
        pass


def run_http_server(port: int, directory: str):
    """Run HTTP server for static files in a separate thread."""
    project_root = Path(directory).parent.parent

    # Configure directory mapping
    MultiDirectoryHandler.directory_map = {
        '/admin': str(project_root / 'admin_panel'),
        '/': str(directory),  # Default to preview_tool/frontend
    }

    # Start server from project root
    os.chdir(str(project_root))

    with socketserver.TCPServer(("", port), MultiDirectoryHandler) as httpd:
        logger.info(f"HTTP server at http://localhost:{port}")
        logger.info(f"  Preview: http://localhost:{port}/")
        logger.info(f"  Admin:   http://localhost:{port}/admin/")
        httpd.serve_forever()


async def main():
    parser = argparse.ArgumentParser(description='AudioViz Browser Preview Server')
    parser.add_argument('--ws-port', type=int, default=8766,
                        help='WebSocket port (default: 8766)')
    parser.add_argument('--http-port', type=int, default=8080,
                        help='HTTP port for web interface (default: 8080)')
    parser.add_argument('--http-only', action='store_true',
                        help='Only run HTTP server (when app_capture.py is running)')
    parser.add_argument('--demo', action='store_true',
                        help='Run with simulated audio data')
    parser.add_argument('--capture-host', type=str, default=None,
                        help='Audio capture host to connect to')
    parser.add_argument('--capture-port', type=int, default=8767,
                        help='Audio capture port (default: 8767)')

    args = parser.parse_args()

    # Start HTTP server in background thread
    frontend_dir = Path(__file__).parent.parent / "frontend"
    http_thread = threading.Thread(
        target=run_http_server,
        args=(args.http_port, str(frontend_dir)),
        daemon=True
    )
    http_thread.start()

    # If http-only mode, just keep running for static file serving
    if args.http_only:
        logger.info(f"HTTP-only mode - connect browsers to app_capture.py WebSocket")
        logger.info(f"Open http://localhost:{args.http_port} for preview")
        logger.info(f"Open http://localhost:{args.http_port}/admin/ for control panel")
        # Keep running
        while True:
            await asyncio.sleep(60)
        return

    # Start WebSocket server
    async with serve(handle_client, "0.0.0.0", args.ws_port):
        logger.info(f"WebSocket server at ws://localhost:{args.ws_port}")
        logger.info(f"Open http://localhost:{args.http_port} in your browser")

        if args.demo or args.capture_host is None:
            # Run demo mode
            await run_demo_mode()
        else:
            # Connect to audio capture
            await connect_to_capture(args.capture_host, args.capture_port)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Server stopped")
