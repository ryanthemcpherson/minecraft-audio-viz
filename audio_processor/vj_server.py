"""
VJ Server - Central server for multi-DJ audio visualization.

Accepts connections from multiple remote DJs, manages active DJ selection,
and forwards visualization data to Minecraft and browser clients.

Usage:
    python -m audio_processor.vj_server --config configs/dj_auth.json

Architecture:
    DJ 1 (Remote) ──┐
    DJ 2 (Remote) ──┼──> VJ Server ──> Minecraft + Browsers
    DJ 3 (Remote) ──┘
                    ↑
                VJ Admin Panel
"""

import argparse
import asyncio
import json
import logging
import signal
import sys
import os
import time
import hashlib
import threading
import http.server
import socketserver
from pathlib import Path
from typing import Optional, Dict, Set, List
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    import websockets
    from websockets.server import serve as ws_serve
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False

from python_client.viz_client import VizClient
from audio_processor.patterns import (
    PatternConfig, AudioState, get_pattern, list_patterns, PATTERNS
)
from audio_processor.spectrograph import TerminalSpectrograph

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('vj_server')


@dataclass
class DJConnection:
    """Represents a connected DJ."""
    dj_id: str
    dj_name: str
    websocket: any
    connected_at: float = field(default_factory=time.time)
    last_frame_at: float = field(default_factory=time.time)
    last_heartbeat: float = field(default_factory=time.time)
    frame_count: int = 0
    priority: int = 10  # Lower = higher priority

    # Last audio state from this DJ
    bands: List[float] = field(default_factory=lambda: [0.0] * 6)
    peak: float = 0.0
    is_beat: bool = False
    beat_intensity: float = 0.0
    bpm: float = 120.0
    seq: int = 0

    # Connection health
    latency_ms: float = 0.0
    frames_per_second: float = 0.0
    _fps_samples: List[float] = field(default_factory=list)

    def update_fps(self):
        """Update FPS calculation."""
        now = time.time()
        self._fps_samples.append(now)
        # Keep last second of samples
        cutoff = now - 1.0
        self._fps_samples = [t for t in self._fps_samples if t > cutoff]
        self.frames_per_second = len(self._fps_samples)


@dataclass
class DJAuthConfig:
    """DJ authentication configuration."""
    djs: Dict[str, dict] = field(default_factory=dict)
    vj_operators: Dict[str, dict] = field(default_factory=dict)

    @classmethod
    def load(cls, filepath: str) -> 'DJAuthConfig':
        """Load auth config from file."""
        try:
            with open(filepath, 'r') as f:
                data = json.load(f)
            return cls(
                djs=data.get('djs', {}),
                vj_operators=data.get('vj_operators', {})
            )
        except Exception as e:
            logger.warning(f"Failed to load auth config: {e}")
            return cls()

    def verify_dj(self, dj_id: str, key: str) -> Optional[dict]:
        """Verify DJ credentials. Returns DJ info if valid."""
        if dj_id not in self.djs:
            return None
        dj = self.djs[dj_id]
        expected_hash = dj.get('key_hash', '')

        # Support plaintext keys for development
        if expected_hash.startswith('sha256:'):
            actual_hash = 'sha256:' + hashlib.sha256(key.encode()).hexdigest()
            if actual_hash != expected_hash:
                return None
        elif expected_hash != key:  # Plaintext comparison
            return None

        return dj

    def verify_vj(self, vj_id: str, key: str) -> Optional[dict]:
        """Verify VJ operator credentials."""
        if vj_id not in self.vj_operators:
            return None
        vj = self.vj_operators[vj_id]
        expected_hash = vj.get('key_hash', '')

        if expected_hash.startswith('sha256:'):
            actual_hash = 'sha256:' + hashlib.sha256(key.encode()).hexdigest()
            if actual_hash != expected_hash:
                return None
        elif expected_hash != key:
            return None

        return vj


class MultiDirectoryHandler(http.server.SimpleHTTPRequestHandler):
    """HTTP handler that serves from multiple directories."""
    directory_map = {}

    def translate_path(self, path):
        path = path.split('?')[0].split('#')[0]
        for url_prefix, fs_directory in self.directory_map.items():
            if path.startswith(url_prefix):
                relative_path = path[len(url_prefix):].lstrip('/')
                return os.path.join(fs_directory, relative_path)
        if '/' in self.directory_map:
            return os.path.join(self.directory_map['/'], path.lstrip('/'))
        return super().translate_path(path)

    def log_message(self, format, *args):
        pass


def run_http_server(port: int, directory: str):
    """Run HTTP server for admin panel."""
    project_root = Path(directory).parent.parent
    admin_dir = project_root / 'admin_panel'
    frontend_dir = project_root / 'preview_tool' / 'frontend'

    MultiDirectoryHandler.directory_map = {
        '/admin': str(admin_dir) if admin_dir.exists() else str(directory),
        '/': str(frontend_dir) if frontend_dir.exists() else str(directory),
    }

    os.chdir(str(project_root))

    with socketserver.TCPServer(("", port), MultiDirectoryHandler) as httpd:
        httpd.serve_forever()


class VJServer:
    """
    Central VJ Server that manages multiple DJ connections.

    Responsibilities:
    - Accept DJ WebSocket connections (port 9000)
    - Authenticate DJs
    - Manage active DJ selection
    - Forward active DJ's audio to visualization
    - Serve VJ admin panel
    - Connect to Minecraft
    """

    def __init__(
        self,
        dj_port: int = 9000,
        broadcast_port: int = 8766,
        http_port: int = 8080,
        minecraft_host: str = "localhost",
        minecraft_port: int = 8765,
        zone: str = "main",
        entity_count: int = 16,
        auth_config: Optional[DJAuthConfig] = None,
        require_auth: bool = False,
        show_spectrograph: bool = True
    ):
        self.dj_port = dj_port
        self.broadcast_port = broadcast_port
        self.http_port = http_port
        self.minecraft_host = minecraft_host
        self.minecraft_port = minecraft_port
        self.zone = zone
        self.entity_count = entity_count
        self.auth_config = auth_config or DJAuthConfig()
        self.require_auth = require_auth

        # DJ management
        self._djs: Dict[str, DJConnection] = {}
        self._active_dj_id: Optional[str] = None
        self._dj_queue: List[str] = []  # Priority queue of DJ IDs

        # Browser clients
        self._broadcast_clients: Set = set()

        # Minecraft client
        self.viz_client: Optional[VizClient] = None

        # Pattern system
        self._pattern_config = PatternConfig(entity_count=entity_count)
        self._current_pattern = get_pattern("spectrum", self._pattern_config)
        self._pattern_name = "spectrum"

        # Spectrograph
        self.spectrograph = TerminalSpectrograph() if show_spectrograph else None

        # Frame counter
        self._frame_count = 0
        self._running = False

        # Fallback state (used when no DJ is active)
        self._fallback_bands = [0.0] * 6
        self._fallback_peak = 0.0
        self._last_frame_time = time.time()

    @property
    def active_dj(self) -> Optional[DJConnection]:
        """Get the currently active DJ."""
        if self._active_dj_id and self._active_dj_id in self._djs:
            return self._djs[self._active_dj_id]
        return None

    def _get_dj_roster(self) -> List[dict]:
        """Get DJ roster for admin panel."""
        roster = []
        for dj_id, dj in self._djs.items():
            roster.append({
                'dj_id': dj_id,
                'dj_name': dj.dj_name,
                'is_active': dj_id == self._active_dj_id,
                'connected_at': dj.connected_at,
                'fps': round(dj.frames_per_second, 1),
                'latency_ms': round(dj.latency_ms, 1),
                'bpm': round(dj.bpm, 1),
                'priority': dj.priority,
                'last_frame_age_ms': round((time.time() - dj.last_frame_at) * 1000, 0)
            })
        # Sort by priority, then by connection time
        roster.sort(key=lambda x: (x['priority'], x['connected_at']))
        return roster

    async def _handle_dj_connection(self, websocket):
        """Handle an incoming DJ connection."""
        dj_id = None

        try:
            # Wait for authentication message
            auth_timeout = 10.0  # 10 second timeout for auth
            try:
                message = await asyncio.wait_for(
                    websocket.recv(),
                    timeout=auth_timeout
                )
            except asyncio.TimeoutError:
                logger.warning("DJ connection timed out waiting for auth")
                await websocket.close(4001, "Authentication timeout")
                return

            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                await websocket.close(4002, "Invalid JSON")
                return

            if data.get('type') != 'dj_auth':
                await websocket.close(4003, "Expected dj_auth message")
                return

            dj_id = data.get('dj_id', '')
            dj_key = data.get('dj_key', '')
            dj_name = data.get('dj_name', dj_id)

            # Verify credentials
            if self.require_auth:
                dj_info = self.auth_config.verify_dj(dj_id, dj_key)
                if not dj_info:
                    logger.warning(f"DJ auth failed: {dj_id}")
                    await websocket.close(4004, "Authentication failed")
                    return
                dj_name = dj_info.get('name', dj_name)
                priority = dj_info.get('priority', 10)
            else:
                priority = 10

            # Check for duplicate connection
            if dj_id in self._djs:
                logger.warning(f"DJ {dj_id} already connected, rejecting duplicate")
                await websocket.close(4005, "Already connected")
                return

            # Create DJ connection
            dj = DJConnection(
                dj_id=dj_id,
                dj_name=dj_name,
                websocket=websocket,
                priority=priority
            )
            self._djs[dj_id] = dj
            self._dj_queue.append(dj_id)

            logger.info(f"DJ connected: {dj_name} ({dj_id})")

            # Send auth success
            await websocket.send(json.dumps({
                'type': 'auth_success',
                'dj_id': dj_id,
                'dj_name': dj_name,
                'is_active': self._active_dj_id == dj_id
            }))

            # If no active DJ, make this one active
            if self._active_dj_id is None:
                await self._set_active_dj(dj_id)

            # Broadcast roster update
            await self._broadcast_dj_roster()

            # Handle incoming frames
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get('type')

                    if msg_type == 'dj_audio_frame':
                        await self._handle_dj_frame(dj, data)

                    elif msg_type == 'dj_heartbeat':
                        dj.last_heartbeat = time.time()
                        await websocket.send(json.dumps({
                            'type': 'heartbeat_ack',
                            'server_time': time.time()
                        }))

                    elif msg_type == 'going_offline':
                        logger.info(f"DJ {dj.dj_name} going offline gracefully")
                        break

                except json.JSONDecodeError:
                    pass

        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.error(f"DJ connection error: {e}")
        finally:
            # Clean up
            if dj_id and dj_id in self._djs:
                del self._djs[dj_id]
                if dj_id in self._dj_queue:
                    self._dj_queue.remove(dj_id)
                logger.info(f"DJ disconnected: {dj_id}")

                # If this was the active DJ, switch to next
                if self._active_dj_id == dj_id:
                    await self._auto_switch_dj()

                await self._broadcast_dj_roster()

    async def _handle_dj_frame(self, dj: DJConnection, data: dict):
        """Process an audio frame from a DJ."""
        dj.seq = data.get('seq', 0)
        dj.bands = data.get('bands', [0.0] * 6)
        dj.peak = data.get('peak', 0.0)
        dj.is_beat = data.get('beat', False)
        dj.beat_intensity = data.get('beat_i', 0.0)
        dj.bpm = data.get('bpm', 120.0)
        dj.last_frame_at = time.time()
        dj.frame_count += 1
        dj.update_fps()

        # Calculate latency if timestamp provided
        if 'ts' in data:
            dj.latency_ms = (time.time() - data['ts']) * 1000

    async def _set_active_dj(self, dj_id: str):
        """Set the active DJ."""
        if dj_id not in self._djs:
            logger.warning(f"Cannot set active DJ: {dj_id} not found")
            return

        old_active = self._active_dj_id
        self._active_dj_id = dj_id

        logger.info(f"Active DJ: {self._djs[dj_id].dj_name}")

        # Notify all DJs of status change
        for did, dj in self._djs.items():
            try:
                await dj.websocket.send(json.dumps({
                    'type': 'status_update',
                    'is_active': did == dj_id
                }))
            except:
                pass

        await self._broadcast_dj_roster()

    async def _auto_switch_dj(self):
        """Automatically switch to next available DJ."""
        if not self._dj_queue:
            self._active_dj_id = None
            logger.info("No DJs available")
            return

        # Find highest priority connected DJ
        available = [dj_id for dj_id in self._dj_queue if dj_id in self._djs]
        if available:
            # Sort by priority
            available.sort(key=lambda x: self._djs[x].priority)
            await self._set_active_dj(available[0])
        else:
            self._active_dj_id = None

    async def _handle_browser_client(self, websocket):
        """Handle browser preview/admin panel connection."""
        self._broadcast_clients.add(websocket)
        logger.info(f"Browser client connected. Total: {len(self._broadcast_clients)}")

        # Send initial state
        await websocket.send(json.dumps({
            'type': 'vj_state',
            'patterns': list_patterns(),
            'current_pattern': self._pattern_name,
            'dj_roster': self._get_dj_roster(),
            'active_dj': self._active_dj_id
        }))

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get('type')

                    if msg_type == 'ping':
                        await websocket.send(json.dumps({'type': 'pong'}))

                    elif msg_type == 'get_state':
                        await websocket.send(json.dumps({
                            'type': 'vj_state',
                            'patterns': list_patterns(),
                            'current_pattern': self._pattern_name,
                            'dj_roster': self._get_dj_roster(),
                            'active_dj': self._active_dj_id
                        }))

                    elif msg_type == 'set_pattern':
                        pattern_name = data.get('pattern', 'spectrum')
                        if pattern_name in PATTERNS:
                            self._pattern_name = pattern_name
                            self._current_pattern = get_pattern(pattern_name, self._pattern_config)
                            await self._broadcast_pattern_change()

                    elif msg_type == 'set_active_dj':
                        dj_id = data.get('dj_id')
                        if dj_id:
                            await self._set_active_dj(dj_id)

                    elif msg_type == 'kick_dj':
                        dj_id = data.get('dj_id')
                        if dj_id and dj_id in self._djs:
                            dj = self._djs[dj_id]
                            try:
                                await dj.websocket.close(4010, "Kicked by VJ")
                            except:
                                pass
                            logger.info(f"DJ kicked: {dj.dj_name}")

                    elif msg_type == 'get_dj_roster':
                        await websocket.send(json.dumps({
                            'type': 'dj_roster',
                            'roster': self._get_dj_roster(),
                            'active_dj': self._active_dj_id
                        }))

                except json.JSONDecodeError:
                    pass

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self._broadcast_clients.discard(websocket)
            logger.info(f"Browser client disconnected. Total: {len(self._broadcast_clients)}")

    async def _broadcast_dj_roster(self):
        """Broadcast DJ roster to all browser clients."""
        message = json.dumps({
            'type': 'dj_roster',
            'roster': self._get_dj_roster(),
            'active_dj': self._active_dj_id
        })
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def _broadcast_pattern_change(self):
        """Broadcast pattern change to all clients."""
        message = json.dumps({
            'type': 'pattern_changed',
            'pattern': self._pattern_name,
            'patterns': list_patterns()
        })
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def _broadcast_viz_state(self, entities: List[dict], bands: List[float],
                                    peak: float, is_beat: bool, beat_intensity: float):
        """Broadcast visualization state to browser clients."""
        if not self._broadcast_clients:
            return

        message = json.dumps({
            'type': 'state',
            'entities': entities,
            'bands': bands,
            'amplitude': peak,
            'is_beat': is_beat,
            'beat_intensity': beat_intensity,
            'frame': self._frame_count,
            'pattern': self._pattern_name,
            'active_dj': self._active_dj_id
        })

        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except:
                self._broadcast_clients.discard(client)

    async def connect_minecraft(self) -> bool:
        """Connect to Minecraft server."""
        self.viz_client = VizClient(self.minecraft_host, self.minecraft_port)

        if not await self.viz_client.connect():
            logger.error(f"Failed to connect to Minecraft at {self.minecraft_host}:{self.minecraft_port}")
            return False

        logger.info(f"Connected to Minecraft at {self.minecraft_host}:{self.minecraft_port}")

        zones = await self.viz_client.get_zones()
        zone_names = [z['name'] for z in zones]

        if self.zone not in zone_names:
            if zone_names:
                self.zone = zone_names[0]
                logger.info(f"Using zone: {self.zone}")
            else:
                logger.error("No zones available!")
                return False

        await self.viz_client.init_pool(self.zone, self.entity_count, "SEA_LANTERN")
        await asyncio.sleep(0.5)

        return True

    def _calculate_entities(self, bands: List[float], peak: float,
                           is_beat: bool, beat_intensity: float) -> List[dict]:
        """Calculate entity positions from audio state."""
        audio_state = AudioState(
            bands=bands,
            amplitude=peak,
            is_beat=is_beat,
            beat_intensity=beat_intensity,
            frame=self._frame_count
        )
        return self._current_pattern.calculate_entities(audio_state)

    async def _update_minecraft(self, entities: List[dict], is_beat: bool, beat_intensity: float):
        """Send entities to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return

        try:
            particles = []
            if is_beat and beat_intensity > 0.2:
                particles.append({
                    'particle': 'NOTE',
                    'x': 0.5, 'y': 0.5, 'z': 0.5,
                    'count': int(20 * beat_intensity)
                })

            await self.viz_client.batch_update_fast(self.zone, entities, particles)
        except Exception as e:
            logger.error(f"Minecraft update error: {e}")

    async def _main_loop(self):
        """Main visualization loop."""
        frame_interval = 0.016  # 60 FPS

        while self._running:
            self._frame_count += 1

            # Get audio from active DJ or use fallback
            dj = self.active_dj
            if dj:
                bands = dj.bands
                peak = dj.peak
                is_beat = dj.is_beat
                beat_intensity = dj.beat_intensity
            else:
                # No active DJ - fade to silence
                bands = self._fallback_bands
                peak = self._fallback_peak
                is_beat = False
                beat_intensity = 0.0

                # Decay fallback values
                for i in range(6):
                    self._fallback_bands[i] *= 0.95
                self._fallback_peak *= 0.95

            # Calculate visualization
            entities = self._calculate_entities(bands, peak, is_beat, beat_intensity)

            # Update spectrograph
            if self.spectrograph:
                dj_info = f" [{dj.dj_name}]" if dj else " [No DJ]"
                self.spectrograph.set_stats(
                    preset="VJ",
                    bpm=dj.bpm if dj else 0,
                    clients=len(self._broadcast_clients),
                    using_fft=True
                )
                self.spectrograph.display(
                    bands=bands,
                    amplitude=peak,
                    is_beat=is_beat,
                    beat_intensity=beat_intensity
                )

            # Send to Minecraft
            await self._update_minecraft(entities, is_beat, beat_intensity)

            # Send to browser clients
            await self._broadcast_viz_state(entities, bands, peak, is_beat, beat_intensity)

            await asyncio.sleep(frame_interval)

    async def run(self):
        """Start the VJ server."""
        if not HAS_WEBSOCKETS:
            logger.error("websockets not installed. Run: pip install websockets")
            return

        self._running = True

        # Start HTTP server for admin panel
        if self.http_port > 0:
            project_root = Path(__file__).parent.parent
            http_thread = threading.Thread(
                target=run_http_server,
                args=(self.http_port, str(project_root / 'audio_processor')),
                daemon=True
            )
            http_thread.start()
            logger.info(f"Admin panel: http://localhost:{self.http_port}/admin/")
            logger.info(f"3D Preview: http://localhost:{self.http_port}/")

        # Start DJ listener
        dj_server = await ws_serve(
            self._handle_dj_connection,
            "0.0.0.0",
            self.dj_port
        )
        logger.info(f"DJ WebSocket server: ws://localhost:{self.dj_port}")

        # Start browser broadcast server
        broadcast_server = await ws_serve(
            self._handle_browser_client,
            "0.0.0.0",
            self.broadcast_port
        )
        logger.info(f"Browser WebSocket: ws://localhost:{self.broadcast_port}")

        logger.info("VJ Server ready. Waiting for DJ connections...")

        try:
            await self._main_loop()
        finally:
            dj_server.close()
            broadcast_server.close()
            await dj_server.wait_closed()
            await broadcast_server.wait_closed()

    def stop(self):
        """Stop the server."""
        self._running = False

    async def cleanup(self):
        """Clean up resources."""
        if self.viz_client and self.viz_client.connected:
            await self.viz_client.set_visible(self.zone, False)
            await self.viz_client.disconnect()

        if self.spectrograph:
            self.spectrograph.clear()


async def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description='VJ Server - Multi-DJ Audio Visualization'
    )
    parser.add_argument('--dj-port', type=int, default=9000,
                       help='Port for DJ connections (default: 9000)')
    parser.add_argument('--broadcast-port', type=int, default=8766,
                       help='Port for browser clients (default: 8766)')
    parser.add_argument('--http-port', type=int, default=8080,
                       help='HTTP port for admin panel (default: 8080)')
    parser.add_argument('--host', type=str, default='192.168.208.1',
                       help='Minecraft server host')
    parser.add_argument('--port', type=int, default=8765,
                       help='Minecraft WebSocket port')
    parser.add_argument('--zone', type=str, default='main',
                       help='Visualization zone')
    parser.add_argument('--entities', type=int, default=16,
                       help='Entity count')
    parser.add_argument('--config', type=str, default='configs/dj_auth.json',
                       help='Path to DJ auth config')
    parser.add_argument('--require-auth', action='store_true',
                       help='Require DJ authentication')
    parser.add_argument('--no-minecraft', action='store_true',
                       help='Run without Minecraft')
    parser.add_argument('--no-spectrograph', action='store_true',
                       help='Disable terminal spectrograph')

    args = parser.parse_args()

    # Load auth config
    auth_config = None
    config_path = Path(args.config)
    if config_path.exists():
        auth_config = DJAuthConfig.load(str(config_path))
        logger.info(f"Loaded auth config: {len(auth_config.djs)} DJs, {len(auth_config.vj_operators)} VJs")
    elif args.require_auth:
        logger.error(f"Auth config not found: {args.config}")
        sys.exit(1)

    # Create server
    server = VJServer(
        dj_port=args.dj_port,
        broadcast_port=args.broadcast_port,
        http_port=args.http_port,
        minecraft_host=args.host,
        minecraft_port=args.port,
        zone=args.zone,
        entity_count=args.entities,
        auth_config=auth_config,
        require_auth=args.require_auth,
        show_spectrograph=not args.no_spectrograph
    )

    # Signal handling
    def signal_handler(sig, frame):
        server.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Connect to Minecraft
    if not args.no_minecraft:
        if not await server.connect_minecraft():
            logger.warning("Continuing without Minecraft...")

    try:
        await server.run()
    finally:
        await server.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
