"""DJ connection management, authentication, queue logic, and coordinator integration."""

from __future__ import annotations

import asyncio
import json
import logging
import math
import re
import time
from pathlib import Path
from typing import TYPE_CHECKING, List, Optional

import msgspec
import msgspec.json as mjson

from vj_server.config import PRESETS as AUDIO_PRESETS
from vj_server.config import ServerConfig
from vj_server.coordinator_client import CoordinatorClient
from vj_server.models import (
    ConnectCode,
    DJConnection,
    _frame_decoder,
    _json_str,
    _sanitize_name,
)

if TYPE_CHECKING:
    import websockets

logger = logging.getLogger("vj_server")


class DJManagerMixin:
    """Mixin providing DJ connection, auth, queue, and coordinator methods.

    Mixed into VJServer -- all methods access shared state via self.
    """

    @property
    def active_dj(self) -> Optional[DJConnection]:
        """Get the currently active DJ."""
        # Snapshot to avoid TOCTOU race on _djs dict
        active_id = self._active_dj_id
        djs = self._djs
        if active_id:
            return djs.get(active_id)
        return None

    def _get_active_dj(self) -> Optional[DJConnection]:
        """Get the currently active DJ (non-property version for metrics)."""
        return self.active_dj

    async def _get_active_dj_safe(self) -> Optional[DJConnection]:
        """Thread-safe version of getting active DJ."""
        async with self._dj_lock:
            if self._active_dj_id and self._active_dj_id in self._djs:
                return self._djs[self._active_dj_id]
            return None

    def _dj_profile_dict(self, dj: DJConnection) -> dict:
        """Build a profile dict for broadcasting to browser clients."""
        return {
            "dj_id": dj.dj_id,
            "dj_name": dj.dj_name,
            "avatar_url": dj.avatar_url,
            "color_palette": dj.color_palette,
            "block_palette": dj.block_palette,
            "slug": dj.slug,
            "bio": dj.bio,
            "genres": dj.genres,
        }

    async def _hydrate_dj_profile(self, dj: DJConnection, dj_session_id: str) -> None:
        """Fetch DJ profile from coordinator and populate DJConnection fields.

        Takes the dj_session_id directly (from the code_auth message) and
        calls the coordinator's internal API. Never raises -- logs warnings
        on failure.
        """
        try:
            profile = await asyncio.wait_for(
                self._coordinator.fetch_dj_profile(dj_session_id),
                timeout=5.0,
            )
            if profile:
                dj.dj_name = profile.get("dj_name", dj.dj_name)
                dj.avatar_url = profile.get("avatar_url")
                dj.color_palette = profile.get("color_palette")
                dj.block_palette = profile.get("block_palette")
                dj.slug = profile.get("slug")
                dj.bio = profile.get("bio")
                dj.genres = profile.get("genres")
                logger.info(
                    "[DJ PROFILE] Loaded profile for %s: slug=%s, %d colors, %d blocks",
                    dj.dj_name,
                    dj.slug,
                    len(dj.color_palette) if dj.color_palette else 0,
                    len(dj.block_palette) if dj.block_palette else 0,
                )
        except Exception as exc:
            logger.warning("[DJ PROFILE] Failed to load profile for %s: %s", dj.dj_id, exc)

    def _get_dj_roster(self) -> List[dict]:
        """Get DJ roster for admin panel.

        Note: This takes a snapshot of the current DJ list. For concurrent-safe
        operations, the caller should use _dj_lock.
        """
        roster = []
        # Take snapshot of current DJs dict to avoid iteration issues
        djs_snapshot = dict(self._djs)
        active_dj_id = self._active_dj_id
        queue_snapshot = list(self._dj_queue)

        for dj_id, dj in djs_snapshot.items():
            # Determine queue position (0-based index in _dj_queue)
            queue_pos = queue_snapshot.index(dj_id) if dj_id in queue_snapshot else 999
            roster.append(
                {
                    "dj_id": dj_id,
                    "dj_name": dj.dj_name,
                    "is_active": dj_id == active_dj_id,
                    "connected_at": dj.connected_at,
                    "fps": round(dj.frames_per_second, 1),
                    "latency_ms": round(dj.latency_ms, 1),
                    "ping_ms": round(dj.network_rtt_ms, 1),
                    "pipeline_latency_ms": round(dj.pipeline_latency_ms, 1),
                    "bpm": round(dj.bpm, 1),
                    "tempo_confidence": round(dj.tempo_confidence, 3),
                    "beat_phase": round(dj.beat_phase, 3),
                    "priority": dj.priority,
                    "last_frame_age_ms": round((time.time() - dj.last_frame_at) * 1000, 0),
                    "direct_mode": dj.direct_mode,
                    "mc_connected": dj.mc_connected if dj.direct_mode else None,
                    "queue_position": queue_pos,
                    "jitter_ms": round(dj._jitter_ms, 1),
                    "clock_sync_count": dj._clock_sync_count,
                    "clock_drift_rate": round(dj._clock_drift_rate * 60 * 1000, 1),  # ms/min
                    "clock_sync_age_s": round(time.time() - dj._last_clock_resync, 0)
                    if dj._last_clock_resync > 0
                    else None,
                    "avatar_url": dj.avatar_url,
                    "color_palette": dj.color_palette,
                    "block_palette": dj.block_palette,
                    "slug": dj.slug,
                    "preset": self._dj_presets.get(dj_id),
                }
            )
        # Sort by queue position (respects manual reordering)
        roster.sort(key=lambda x: x["queue_position"])
        return roster

    async def _process_dj_heartbeat(self, dj: "DJConnection", websocket, frame_data: dict) -> None:
        """Process a dj_heartbeat message (shared between code_auth and dj_auth paths)."""
        now = time.time()
        dj.last_heartbeat = now
        reported_latency_ms = frame_data.get("latency_ms")
        heartbeat_ts = frame_data.get("ts")
        rtt_ms = None
        if isinstance(reported_latency_ms, (int, float)) and math.isfinite(reported_latency_ms):
            rtt_ms = max(0.0, min(float(reported_latency_ms), 60_000.0))
        elif isinstance(heartbeat_ts, (int, float)) and math.isfinite(heartbeat_ts):
            corrected_ts = (
                float(heartbeat_ts) - dj.clock_offset if dj.clock_sync_done else float(heartbeat_ts)
            )
            rtt_ms = max(0.0, min((now - corrected_ts) * 1000.0, 60_000.0))
        if rtt_ms is not None:
            if dj.network_rtt_ms > 0:
                dj.network_rtt_ms = dj.network_rtt_ms * 0.8 + rtt_ms * 0.2
            else:
                dj.network_rtt_ms = rtt_ms
            dj.latency_ms = dj.network_rtt_ms
            dj._rtt_samples.append(rtt_ms)
        if dj.direct_mode:
            dj.mc_connected = frame_data.get("mc_connected", False)
        await websocket.send(
            _json_str(
                {
                    "type": "heartbeat_ack",
                    "server_time": now,
                    "echo_ts": heartbeat_ts,
                }
            )
        )
        # Periodic clock resync (every 30s)
        if dj.clock_sync_done and now - dj._last_clock_resync >= 30.0:
            try:
                await websocket.send(_json_str({"type": "clock_sync_request", "server_time": now}))
                dj._last_clock_resync = now
            except Exception:
                pass

    def _check_auth_rate_limit(self, ip: str) -> bool:
        """Check if an IP has exceeded the auth rate limit. Returns True if rate limited."""
        now = time.time()
        window = self._auth_rate_limit_window

        # Periodic cleanup: by size threshold or every 60 seconds
        if len(self._auth_attempts) > 50 or (now - self._auth_last_cleanup) >= 60.0:
            stale_ips = [
                addr
                for addr, timestamps in self._auth_attempts.items()
                if not timestamps or timestamps[-1] < now - window
            ]
            for addr in stale_ips:
                del self._auth_attempts[addr]
            self._auth_last_cleanup = now

        if ip not in self._auth_attempts:
            self._auth_attempts[ip] = []

        # Remove timestamps outside the window
        attempts = self._auth_attempts[ip]
        self._auth_attempts[ip] = [t for t in attempts if t > now - window]
        attempts = self._auth_attempts[ip]

        if len(attempts) >= self._auth_rate_limit_max:
            return True  # rate limited

        # Record this attempt
        attempts.append(now)
        return False

    async def _handle_dj_connection(self, websocket):
        """Handle an incoming DJ connection."""
        dj_id = None

        try:
            # Wait for authentication message
            auth_timeout = 10.0  # 10 second timeout for auth
            try:
                message = await asyncio.wait_for(websocket.recv(), timeout=auth_timeout)
            except asyncio.TimeoutError:
                logger.warning("DJ connection timed out waiting for auth")
                await websocket.close(4001, "Authentication timeout")
                return

            try:
                data = mjson.decode(message)
            except msgspec.DecodeError:
                await websocket.close(4002, "Invalid JSON")
                return

            # Rate limit auth attempts per IP
            client_ip = websocket.remote_address[0] if websocket.remote_address else "unknown"
            if self._check_auth_rate_limit(client_ip):
                logger.warning("Auth rate limited for IP %s", client_ip)
                await websocket.close(4029, "Too many auth attempts, try again later")
                return

            msg_type = data.get("type")

            # Support both traditional auth and code-based auth
            if msg_type == "code_auth":
                # Code-based authentication (from DJ client)
                code = data.get("code", "").upper()
                dj_name = _sanitize_name(data.get("dj_name", "DJ"), default="DJ")

                # Slur filter on DJ name
                try:
                    from vj_server.content_filter import contains_slur as _contains_slur

                    if _contains_slur(dj_name):
                        logger.warning("DJ code auth rejected: DJ name failed content filter")
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "auth_error",
                                    "error": "DJ name contains language that is not allowed",
                                }
                            )
                        )
                        await websocket.close(4005, "Content policy violation")
                        return
                except ImportError:
                    logger.warning(
                        "better-profanity not installed -- DJ name content filter disabled"
                    )

                # Validate connect code (locked to prevent race condition
                # where two concurrent auths could both pass is_valid())
                async with self._dj_lock:
                    if code not in self._connect_codes:
                        logger.warning(f"DJ code auth failed: invalid code {code}")
                        await websocket.send(
                            _json_str({"type": "auth_error", "error": "Invalid connect code"})
                        )
                        await websocket.close(4004, "Invalid connect code")
                        return

                    connect_code = self._connect_codes[code]
                    if not connect_code.is_valid():
                        logger.warning(f"DJ code auth failed: expired code {code}")
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "auth_error",
                                    "error": "Connect code has expired",
                                }
                            )
                        )
                        await websocket.close(4004, "Connect code expired")
                        return

                    # Mark code as used (atomically with validation)
                    connect_code.used = True

                # Generate a unique DJ ID for code-authenticated users
                dj_id = f"dj_{code.replace('-', '_').lower()}"
                priority = 10  # Default priority for code-authenticated DJs

                logger.info(f"DJ code auth successful: {dj_name} with code {code}")

                # Connect-code DJs go into pending approval queue
                direct_mode = data.get("direct_mode", False)
                pending_info = {
                    "dj_id": dj_id,
                    "dj_name": dj_name,
                    "websocket": websocket,
                    "waiting_since": time.time(),
                    "direct_mode": direct_mode,
                    "priority": priority,
                    "code": code,
                    "dj_session_id": data.get("dj_session_id"),  # For profile lookup
                }
                self._pending_djs[dj_id] = pending_info

                # Tell the DJ they're waiting for approval
                await websocket.send(
                    _json_str(
                        {
                            "type": "auth_pending",
                            "message": "Waiting for VJ approval...",
                            "dj_id": dj_id,
                        }
                    )
                )

                # Notify admin panel
                await self._broadcast_to_browsers(
                    _json_str(
                        {
                            "type": "dj_pending",
                            "dj": {
                                "dj_id": dj_id,
                                "dj_name": dj_name,
                                "waiting_since": pending_info["waiting_since"],
                                "direct_mode": direct_mode,
                            },
                        }
                    )
                )

                logger.info(f"DJ {dj_name} ({dj_id}) placed in approval queue")

                # Wait for approval or denial (the DJ stays connected)
                try:
                    while dj_id in self._pending_djs:
                        # Check for messages from the pending DJ (heartbeat/disconnect)
                        try:
                            msg = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                            if len(msg) > 65536:
                                continue
                            msg_data = mjson.decode(msg)
                            if msg_data.get("type") == "ping":
                                await websocket.send(_json_str({"type": "pong"}))
                        except asyncio.TimeoutError:
                            # Just a timeout on recv, keep waiting
                            pass
                        except websockets.exceptions.ConnectionClosed:
                            # DJ disconnected while waiting
                            self._pending_djs.pop(dj_id, None)
                            logger.info(
                                f"Pending DJ {dj_name} disconnected while waiting for approval"
                            )
                            await self._broadcast_to_browsers(
                                _json_str(
                                    {
                                        "type": "dj_denied",
                                        "dj_id": dj_id,
                                    }
                                )
                            )
                            return
                except Exception as e:
                    self._pending_djs.pop(dj_id, None)
                    logger.warning(f"Error in pending DJ wait loop: {e}")
                    return

                # If we get here, the DJ was removed from pending (approved or denied)
                # Check if they were approved (they'll be in self._djs by now)
                if dj_id not in self._djs:
                    # They were denied
                    return

                # They were approved - run the frame handling loop
                dj = self._djs[dj_id]

                # Perform clock synchronization (same as dj_auth path).
                # The DJ's heartbeat task may already be running, so we drain
                # any non-sync messages while waiting for the sync response.
                try:
                    t1 = time.time()
                    await websocket.send(
                        _json_str({"type": "clock_sync_request", "server_time": t1})
                    )
                    sync_deadline = asyncio.get_running_loop().time() + 5.0
                    while True:
                        remaining = sync_deadline - asyncio.get_running_loop().time()
                        if remaining <= 0:
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: timeout waiting for sync response"
                            )
                            break
                        raw = await asyncio.wait_for(websocket.recv(), timeout=remaining)
                        t4 = time.time()
                        sync_data = mjson.decode(raw)

                        if sync_data.get("type") != "clock_sync_response":
                            # Interleaved heartbeat or other message -- process and retry
                            if sync_data.get("type") == "dj_heartbeat":
                                dj.last_heartbeat = t4
                            continue

                        t2 = sync_data.get("dj_recv_time", t1)
                        t3 = sync_data.get("dj_send_time", t4)

                        if (
                            not isinstance(t2, (int, float))
                            or not isinstance(t3, (int, float))
                            or not math.isfinite(t2)
                            or not math.isfinite(t3)
                        ):
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: non-finite timestamps, skipping"
                            )
                        elif abs(t2 - t1) > 3600 or abs(t3 - t4) > 3600:
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: timestamps too far, skipping"
                            )
                        else:
                            clock_offset = ((t2 - t1) + (t3 - t4)) / 2
                            rtt = (t4 - t1) - (t3 - t2)
                            if rtt < 0 or rtt > 30:
                                logger.warning(
                                    f"[DJ CLOCK SYNC] {dj_name}: invalid RTT={rtt * 1000:.1f}ms"
                                )
                            else:
                                dj.clock_offset = clock_offset
                                dj.clock_sync_done = True
                                dj._last_clock_resync = time.time()
                                dj._clock_sync_count = 1
                                logger.info(
                                    f"[DJ CLOCK SYNC] {dj_name}: offset={clock_offset * 1000:.1f}ms, RTT={rtt * 1000:.1f}ms"
                                )
                        break
                except asyncio.TimeoutError:
                    logger.warning(f"[DJ CLOCK SYNC] {dj_name}: timeout waiting for sync response")
                except Exception as e:
                    logger.warning(f"[DJ CLOCK SYNC] {dj_name}: sync failed: {e}")

                # Send explicit stream route (same as dj_auth path)
                try:
                    await websocket.send(_json_str(self._build_stream_route_message(dj_id, dj)))
                except Exception as e:
                    logger.debug(f"Failed to send stream route to DJ {dj_id}: {e}")

                # Handle incoming frames (same pattern as credentialed DJs)
                async for message in websocket:
                    try:
                        # Fast path: audio frames are ~95% of DJ traffic.
                        # Substring check + typed decode avoids a generic decode.
                        if isinstance(message, str) and '"dj_audio_frame"' in message:
                            frame = _frame_decoder.decode(message)
                            await self._handle_dj_frame(dj, frame)
                            continue

                        frame_data = mjson.decode(message)
                        frame_type = frame_data.get("type")

                        if frame_type == "dj_heartbeat":
                            await self._process_dj_heartbeat(dj, websocket, frame_data)
                        elif frame_type == "clock_sync_response":
                            self._apply_clock_resync(dj, frame_data)
                        elif frame_type == "voice_audio":
                            # Relay voice audio from active DJ to Minecraft
                            dj.voice_streaming = True
                            if dj.dj_id == self._active_dj_id:
                                await self._relay_voice_audio(frame_data)
                        elif frame_type == "going_offline":
                            logger.info(
                                f"[DJ GOING OFFLINE] {dj.dj_name} ({dj.dj_id}) going offline gracefully"
                            )
                            break
                    except msgspec.DecodeError:
                        logger.debug(f"Invalid JSON from DJ {dj_name}")
                    except Exception as e:
                        logger.error(
                            f"Error processing DJ frame from {dj_name}: {e}",
                            exc_info=True,
                        )
                return

            elif msg_type == "dj_auth":
                # Traditional credential-based authentication
                dj_id = _sanitize_name(data.get("dj_id", ""), max_length=64, default="")
                dj_key = data.get("dj_key", "")
                dj_name = _sanitize_name(data.get("dj_name", dj_id), default=dj_id or "DJ")

                # Slur filter on DJ name
                try:
                    from vj_server.content_filter import contains_slur as _contains_slur

                    if _contains_slur(dj_name):
                        logger.warning("DJ auth rejected: DJ name failed content filter")
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "auth_error",
                                    "error": "DJ name contains language that is not allowed",
                                }
                            )
                        )
                        await websocket.close(4005, "Content policy violation")
                        return
                except ImportError:
                    logger.warning(
                        "better-profanity not installed -- DJ name content filter disabled"
                    )

                # Verify credentials
                if self.require_auth:
                    dj_info = self.auth_config.verify_dj(dj_id, dj_key)
                    if not dj_info:
                        logger.warning(f"DJ auth failed: {dj_id}")
                        await websocket.close(4004, "Authentication failed")
                        return
                    dj_name = dj_info.get("name", dj_name)
                    priority = dj_info.get("priority", 10)
                else:
                    priority = 10
            else:
                await websocket.close(4003, "Expected dj_auth or code_auth message")
                return

            # Check for duplicate connection and capacity (with lock)
            MAX_DJ_CONNECTIONS = 10
            async with self._dj_lock:
                if dj_id in self._djs:
                    logger.warning(f"DJ {dj_id} already connected, rejecting duplicate")
                    await websocket.close(4005, "Already connected")
                    return

                if len(self._djs) >= MAX_DJ_CONNECTIONS:
                    await websocket.close(4003, "DJ connection limit reached")
                    return

                # Check if DJ is using direct mode
                direct_mode = data.get("direct_mode", False)

                # Create DJ connection
                dj = DJConnection(
                    dj_id=dj_id,
                    dj_name=dj_name,
                    websocket=websocket,
                    priority=priority,
                    direct_mode=direct_mode,
                )
                self._djs[dj_id] = dj
                self._dj_queue.append(dj_id)

            mode_str = " (DIRECT)" if direct_mode else ""
            logger.info(
                f"[DJ CONNECT] {dj_name} ({dj_id}){mode_str} from {websocket.remote_address}"
            )
            self._dj_connects += 1

            # Fetch DJ profile from coordinator (non-blocking, best-effort)
            dj_session_id = data.get("dj_session_id")
            if dj_session_id and self._coordinator:
                await self._hydrate_dj_profile(dj, dj_session_id)
                dj_name = dj.dj_name  # Update local var for auth_response

            # Build auth success response
            auth_response = {
                "type": "auth_success",
                "dj_id": dj_id,
                "dj_name": dj_name,
                "is_active": self._active_dj_id == dj_id,
                # Pattern info for direct mode
                "current_pattern": self._pattern_name,
                "pattern_config": {
                    "entity_count": self.entity_count,
                    "zone_size": self._pattern_config.zone_size,
                    "beat_boost": self._pattern_config.beat_boost,
                    "base_scale": self._pattern_config.base_scale,
                    "max_scale": self._pattern_config.max_scale,
                },
            }

            # Include Minecraft connection info for direct mode DJs
            if direct_mode:
                auth_response["minecraft_host"] = self.minecraft_host
                auth_response["minecraft_port"] = self.minecraft_port
                auth_response["zone"] = self.zone
                auth_response["entity_count"] = self.entity_count
            # Initial route hint for newer clients (legacy clients ignore unknown fields)
            auth_response["route_mode"] = (
                "dual" if (direct_mode and self._active_dj_id == dj_id) else "relay"
            )

            await websocket.send(_json_str(auth_response))
            logger.info(f"[DJ AUTH SUCCESS] {dj_name} ({dj_id}) authenticated, priority={priority}")

            # Perform clock synchronization to handle clock skew between DJ and server
            # Uses NTP-style algorithm: send server time, DJ responds with its time
            try:
                t1 = time.time()  # Server time when sync request sent
                await websocket.send(_json_str({"type": "clock_sync_request", "server_time": t1}))
                # Wait for DJ response with timeout
                sync_response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                t4 = time.time()  # Server time when response received
                sync_data = mjson.decode(sync_response)

                if sync_data.get("type") == "clock_sync_response":
                    t2 = sync_data.get("dj_recv_time", t1)  # DJ time when it received request
                    t3 = sync_data.get("dj_send_time", t4)  # DJ time when it sent response

                    # Validate clock sync values are finite and within reasonable range
                    if (
                        not isinstance(t2, (int, float))
                        or not isinstance(t3, (int, float))
                        or not math.isfinite(t2)
                        or not math.isfinite(t3)
                    ):
                        logger.warning(
                            f"[DJ CLOCK SYNC] {dj_name}: non-finite timestamps, skipping sync"
                        )
                    elif abs(t2 - t1) > 3600 or abs(t3 - t4) > 3600:
                        logger.warning(
                            f"[DJ CLOCK SYNC] {dj_name}: timestamps too far from server time (>1h), skipping sync"
                        )
                    else:
                        # Calculate clock offset using NTP algorithm
                        # offset = ((t2 - t1) + (t3 - t4)) / 2
                        # Positive offset means DJ clock is ahead of server
                        clock_offset = ((t2 - t1) + (t3 - t4)) / 2
                        rtt = (t4 - t1) - (t3 - t2)  # Round-trip time

                        if rtt < 0 or rtt > 30:
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: invalid RTT={rtt * 1000:.1f}ms, skipping sync"
                            )
                        else:
                            dj.clock_offset = clock_offset
                            dj.clock_sync_done = True
                            dj._last_clock_resync = time.time()
                            dj._clock_sync_count = 1
                            logger.info(
                                f"[DJ CLOCK SYNC] {dj_name}: offset={clock_offset * 1000:.1f}ms, RTT={rtt * 1000:.1f}ms"
                            )
                else:
                    actual_type = sync_data.get("type", "unknown")
                    logger.warning(
                        f"[DJ CLOCK SYNC] {dj_name}: expected 'clock_sync_response' but got '{actual_type}', skipping sync"
                    )
            except asyncio.TimeoutError:
                logger.warning(f"[DJ CLOCK SYNC] {dj_name}: timeout waiting for sync response")
            except Exception as e:
                logger.warning(f"[DJ CLOCK SYNC] {dj_name}: sync failed: {e}")

            # Send explicit routing policy after handshake.
            try:
                await websocket.send(_json_str(self._build_stream_route_message(dj_id, dj)))
            except Exception as e:
                logger.debug(f"Failed to send initial stream route to DJ {dj_id}: {e}")

            # If no active DJ, make this one active
            if self._active_dj_id is None:
                await self._set_active_dj(dj_id)

            # Broadcast roster update
            await self._broadcast_dj_roster()

            # Broadcast DJ joined with full profile to browser clients
            await self._broadcast_to_browsers(
                _json_str(
                    {
                        "type": "dj_joined",
                        "dj": self._dj_profile_dict(dj),
                    }
                )
            )

            # Handle incoming frames
            async for message in websocket:
                try:
                    # Fast path: audio frames are ~95% of DJ traffic.
                    # Substring check + typed decode avoids a generic decode.
                    if isinstance(message, str) and '"dj_audio_frame"' in message:
                        frame = _frame_decoder.decode(message)
                        await self._handle_dj_frame(dj, frame)
                        continue

                    data = mjson.decode(message)
                    msg_type = data.get("type")

                    if msg_type == "dj_heartbeat":
                        await self._process_dj_heartbeat(dj, websocket, data)

                    elif msg_type == "clock_sync_response":
                        self._apply_clock_resync(dj, data)

                    elif msg_type == "voice_audio":
                        # Relay voice audio from active DJ to Minecraft
                        dj.voice_streaming = True
                        if dj.dj_id == self._active_dj_id:
                            await self._relay_voice_audio(data)

                    elif msg_type == "set_my_palette":
                        palette = data.get("band_materials")
                        if isinstance(palette, list) and len(palette) == 5:
                            cleaned = [(m if isinstance(m, str) and m else None) for m in palette]
                            self._dj_palettes[dj.dj_id] = cleaned
                            logger.info(f"DJ {dj.dj_name} set block palette: {cleaned}")
                            # If this DJ is currently active, apply immediately
                            if dj.dj_id == self._active_dj_id:
                                self._band_materials = list(cleaned)
                                self._band_materials_source = "dj_palette"
                                await self._broadcast_to_browsers(
                                    _json_str(
                                        {
                                            "type": "band_materials_sync",
                                            "materials": self._band_materials,
                                            "source": self._band_materials_source,
                                        }
                                    )
                                )
                        else:
                            logger.warning(f"Invalid set_my_palette from DJ {dj.dj_id}")

                    elif msg_type == "set_my_preset":
                        preset_name = data.get("preset")
                        if isinstance(preset_name, str) and preset_name.lower() in AUDIO_PRESETS:
                            preset_name = preset_name.lower()
                            self._dj_presets[dj.dj_id] = preset_name
                            logger.info(f"DJ {dj.dj_name} set preferred preset: {preset_name}")
                            # If this DJ is currently active, apply immediately
                            if dj.dj_id == self._active_dj_id:
                                preset_dict = self._apply_named_preset(preset_name)
                                await self._broadcast_preset_to_djs(preset_dict, preset_name)
                        else:
                            logger.warning(
                                f"Invalid set_my_preset from DJ {dj.dj_id}: {preset_name}"
                            )

                    elif msg_type == "going_offline":
                        logger.info(
                            f"[DJ GOING OFFLINE] {dj.dj_name} ({dj.dj_id}) going offline gracefully"
                        )
                        break

                    else:
                        logger.debug(f"Unknown message type from DJ {dj.dj_id}: {msg_type}")

                except msgspec.DecodeError as e:
                    logger.warning(f"Invalid JSON from DJ {dj.dj_id}: {e}")

        except websockets.exceptions.ConnectionClosed as e:
            dj_name = (
                self._djs[dj_id].dj_name if dj_id and dj_id in self._djs else dj_id or "unknown"
            )
            logger.info(
                f"DJ {dj_name} ({dj_id}) connection closed: code={e.code}, reason={e.reason}"
            )
        except Exception as e:
            logger.error(f"DJ connection error: {e}", exc_info=True)
        finally:
            # Clean up pending DJs
            if dj_id and dj_id in self._pending_djs:
                self._pending_djs.pop(dj_id, None)

            # Clean up active DJs (with lock)
            if dj_id:
                _left_dj_name = None
                async with self._dj_lock:
                    if dj_id in self._djs:
                        _left_dj_name = self._djs[dj_id].dj_name
                        del self._djs[dj_id]
                        self._dj_palettes.pop(dj_id, None)
                        self._dj_presets.pop(dj_id, None)
                        if dj_id in self._dj_queue:
                            self._dj_queue.remove(dj_id)
                        logger.info(f"[DJ DISCONNECT] {_left_dj_name} ({dj_id})")
                        self._dj_disconnects += 1

                        # If this was the active DJ, switch to next
                        if self._active_dj_id == dj_id:
                            await self._auto_switch_dj_locked()

                # Broadcast DJ left to browser clients
                if _left_dj_name:
                    await self._broadcast_to_browsers(
                        _json_str(
                            {
                                "type": "dj_left",
                                "dj_id": dj_id,
                                "dj_name": _left_dj_name,
                            }
                        )
                    )

                await self._broadcast_dj_roster()

    async def _set_active_dj(self, dj_id: str):
        """Set the active DJ."""
        async with self._dj_lock:
            await self._set_active_dj_locked(dj_id)

    async def _set_active_dj_locked(self, dj_id: str):
        """Set the active DJ (caller must hold _dj_lock)."""
        if dj_id not in self._djs:
            logger.warning(f"Cannot set active DJ: {dj_id} not found")
            return

        self._active_dj_id = dj_id
        self._beat_predictor.reset()

        # Apply DJ's block palette (if set)
        palette = self._dj_palettes.get(dj_id)
        if palette and any(m is not None for m in palette):
            self._band_materials = list(palette)
            self._band_materials_source = "dj_palette"
        else:
            self._band_materials = [None, None, None, None, None]
            self._band_materials_source = "default"

        # Restore DJ's preferred audio preset, or reset to "auto" if none stored
        stored_preset = self._dj_presets.get(dj_id)
        if not stored_preset or stored_preset not in AUDIO_PRESETS:
            stored_preset = "auto"
        stored_preset_dict = self._apply_named_preset(stored_preset)
        logger.info(f"Preset '{stored_preset}' for DJ {self._djs[dj_id].dj_name}")

        logger.info(f"Active DJ: {self._djs[dj_id].dj_name}")

        # Take snapshot for notification (release lock during network IO)
        djs_snapshot = list(self._djs.items())

        # Notify all DJs of status change (outside lock to avoid deadlock)
        for did, dj in djs_snapshot:
            try:
                await dj.websocket.send(
                    _json_str({"type": "status_update", "is_active": did == dj_id})
                )
            except Exception as e:
                logger.debug(f"Failed to send status to DJ {did}: {e}")
            # Push per-DJ stream routing policy after every active switch.
            try:
                await dj.websocket.send(_json_str(self._build_stream_route_message(did, dj)))
            except Exception as e:
                logger.debug(f"Failed to send stream route to DJ {did}: {e}")

        await self._broadcast_dj_roster()

        # Broadcast band materials after DJ switch
        await self._broadcast_to_browsers(
            _json_str(
                {
                    "type": "band_materials_sync",
                    "materials": self._band_materials,
                    "source": self._band_materials_source,
                }
            )
        )

        # Broadcast preset to all DJs + browsers after DJ switch
        await self._broadcast_preset_to_djs(stored_preset_dict, stored_preset)

        # Send dj_info to Minecraft for stage decorators (billboard, transitions)
        await self._send_dj_info_to_minecraft(dj_id)

    async def _auto_switch_dj(self):
        """Automatically switch to next available DJ."""
        async with self._dj_lock:
            await self._auto_switch_dj_locked()

    async def _auto_switch_dj_locked(self):
        """Automatically switch to next available DJ (caller must hold _dj_lock)."""
        if not self._dj_queue:
            self._active_dj_id = None
            self._beat_predictor.reset()
            logger.info("No DJs available")
            await self._send_dj_info_to_minecraft(None)
            return

        # Find highest priority connected DJ
        available = [dj_id for dj_id in self._dj_queue if dj_id in self._djs]
        if available:
            # Sort by priority
            available.sort(key=lambda x: self._djs[x].priority)
            await self._set_active_dj_locked(available[0])
        else:
            self._active_dj_id = None
            self._beat_predictor.reset()
            await self._send_dj_info_to_minecraft(None)

    async def _approve_pending_dj(self, dj_id: str):
        """Approve a pending DJ and move them to the active DJ list."""
        async with self._dj_lock:
            if not dj_id or dj_id not in self._pending_djs:
                logger.warning(f"Cannot approve DJ {dj_id}: not in pending queue")
                return

            info = self._pending_djs.pop(dj_id)
            ws = info["websocket"]
            if dj_id in self._djs:
                logger.warning(f"DJ {dj_id} already in active list")
                return

            MAX_DJ_CONNECTIONS = 10
            if len(self._djs) >= MAX_DJ_CONNECTIONS:
                logger.warning(f"DJ connection limit reached, cannot approve {dj_id}")
                return

            dj = DJConnection(
                dj_id=dj_id,
                dj_name=info["dj_name"],
                websocket=ws,
                priority=info.get("priority", 10),
                direct_mode=info.get("direct_mode", False),
            )
            self._djs[dj_id] = dj
            self._dj_queue.append(dj_id)

        self._dj_connects += 1
        logger.info(f"[DJ APPROVED] {info['dj_name']} ({dj_id})")

        # Fetch DJ profile from coordinator (non-blocking, best-effort)
        dj_session_id = info.get("dj_session_id")
        if dj_session_id and self._coordinator:
            await self._hydrate_dj_profile(dj, dj_session_id)

        # Send auth_success to the DJ (same type as dj_auth path,
        # so the Rust client handles it via the existing ServerMessage enum)
        try:
            await ws.send(
                _json_str(
                    {
                        "type": "auth_success",
                        "dj_id": dj_id,
                        "dj_name": dj.dj_name,
                        "is_active": self._active_dj_id == dj_id,
                        "current_pattern": self._pattern_name,
                        "pattern_config": {
                            "entity_count": self.entity_count,
                            "zone_size": self._pattern_config.zone_size,
                            "beat_boost": self._pattern_config.beat_boost,
                            "base_scale": self._pattern_config.base_scale,
                            "max_scale": self._pattern_config.max_scale,
                        },
                    }
                )
            )
        except Exception as e:
            logger.warning(f"Failed to send auth_success to DJ {dj_id}: {e}")

        # If no active DJ, make this one active
        if self._active_dj_id is None:
            await self._set_active_dj(dj_id)

        # Broadcast roster update
        await self._broadcast_dj_roster()
        await self._broadcast_stream_routes()
        await self._broadcast_to_browsers(
            _json_str(
                {
                    "type": "dj_approved",
                    "dj_id": dj_id,
                }
            )
        )

        # Broadcast DJ joined with full profile to browser clients
        dj = self._djs.get(dj_id)
        if dj:
            await self._broadcast_to_browsers(
                _json_str(
                    {
                        "type": "dj_joined",
                        "dj": self._dj_profile_dict(dj),
                    }
                )
            )

    async def _deny_pending_dj(self, dj_id: str):
        """Deny a pending DJ and close their connection."""
        if not dj_id or dj_id not in self._pending_djs:
            logger.warning(f"Cannot deny DJ {dj_id}: not in pending queue")
            return

        info = self._pending_djs.pop(dj_id)
        ws = info["websocket"]

        logger.info(f"[DJ DENIED] {info['dj_name']} ({dj_id})")

        # Send denial and close
        try:
            await ws.send(
                _json_str(
                    {
                        "type": "auth_denied",
                        "message": "Connection denied by VJ",
                    }
                )
            )
            await ws.close(4006, "Connection denied by VJ")
        except Exception:
            pass

        await self._broadcast_to_browsers(
            _json_str(
                {
                    "type": "dj_denied",
                    "dj_id": dj_id,
                }
            )
        )

    async def _reorder_dj_queue(self, dj_id: str, new_position: int):
        """Move a DJ to a new position in the queue."""
        async with self._dj_lock:
            if dj_id not in self._dj_queue:
                return
            self._dj_queue.remove(dj_id)
            new_position = max(0, min(len(self._dj_queue), new_position))
            self._dj_queue.insert(new_position, dj_id)
            logger.info(f"DJ queue reordered: {dj_id} -> position {new_position}")

        await self._broadcast_dj_roster()

    def _apply_named_preset(self, preset_name: str) -> dict:
        """Apply a named audio preset to server state.

        Updates pattern_config, band_sensitivity, and current_preset_name.
        Returns the preset as a dict for broadcasting.
        """
        config = AUDIO_PRESETS[preset_name]
        self._pattern_config.attack = config.attack
        self._pattern_config.release = config.release
        self._pattern_config.beat_threshold = config.beat_threshold
        self._band_sensitivity = list(config.band_sensitivity)
        self._current_preset_name = preset_name
        return config.to_dict()

    def _build_stream_route_message(self, dj_id: str, dj: DJConnection) -> dict:
        """Build stream routing policy for a DJ client.

        route_mode:
        - relay: DJ sends audio to VJ only (default / standby DJs)
        - dual: DJ sends audio to VJ and publishes visualization directly to Minecraft
        """
        is_active = self._active_dj_id == dj_id
        route_mode = "dual" if (dj.direct_mode and is_active) else "relay"

        return {
            "type": "stream_route",
            "route_mode": route_mode,
            "is_active": is_active,
            "minecraft_host": self.minecraft_host,
            "minecraft_port": self.minecraft_port,
            "zone": self.zone,
            "entity_count": self.entity_count,
            "current_pattern": self._pattern_name,
            "pattern_config": {
                "entity_count": self.entity_count,
                "zone_size": self._pattern_config.zone_size,
                "beat_boost": self._pattern_config.beat_boost,
                "base_scale": self._pattern_config.base_scale,
                "max_scale": self._pattern_config.max_scale,
            },
            "pattern_scripts": self._get_pattern_scripts(),
            "band_sensitivity": list(self._band_sensitivity),
            "preset": self._current_preset_name,
            "relay_fallback": True,
            "reason": "active_direct_dj" if route_mode == "dual" else "standby_or_relay_mode",
        }

    async def _broadcast_stream_routes(self):
        """Broadcast routing policy to all connected DJs."""
        async with self._dj_lock:
            djs_snapshot = list(self._djs.items())
        for did, dj in djs_snapshot:
            try:
                await dj.websocket.send(_json_str(self._build_stream_route_message(did, dj)))
            except Exception as e:
                logger.debug(f"Failed to broadcast stream route to DJ {did}: {e}")

    async def _send_dj_info_to_minecraft(self, dj_id: Optional[str]):
        """Send DJ info to Minecraft plugin for stage decorator effects."""
        if not self.viz_client or not self.viz_client.connected:
            return

        if dj_id and dj_id in self._djs:
            dj = self._djs[dj_id]
            msg = {
                "type": "dj_info",
                "dj_name": dj.dj_name,
                "dj_id": dj.dj_id,
                "bpm": dj.bpm,
                "is_active": True,
            }
        else:
            msg = {
                "type": "dj_info",
                "dj_name": "",
                "dj_id": "",
                "bpm": 0.0,
                "is_active": False,
            }

        try:
            await self.viz_client.send(msg)
            logger.debug(f"Sent dj_info to Minecraft: {msg.get('dj_name', 'none')}")
        except Exception as e:
            logger.debug(f"Failed to send dj_info to Minecraft: {e}")

        # Also send banner config for the active DJ
        await self._send_banner_config_to_minecraft(dj_id)

    async def _send_banner_config_to_minecraft(self, dj_id: Optional[str]):
        """Send banner config for the active DJ to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return

        profile = self._dj_banner_profiles.get(dj_id, {}) if dj_id else {}

        msg = {
            "type": "banner_config",
            "banner_mode": profile.get("banner_mode", "text"),
            "text_style": profile.get("text_style", "bold"),
            "text_color_mode": profile.get("text_color_mode", "frequency"),
            "text_fixed_color": profile.get("text_fixed_color", "f"),
            "text_format": profile.get("text_format", "%s"),
            "grid_width": profile.get("grid_width", 24),
            "grid_height": profile.get("grid_height", 12),
            "image_pixels": profile.get("image_pixels", []),
        }

        try:
            await self.viz_client.send(msg)
            logger.debug(f"Sent banner_config to Minecraft for DJ: {dj_id}")
        except Exception as e:
            logger.debug(f"Failed to send banner_config: {e}")

    # ========== Banner Profile Management ==========

    def _load_banner_profiles(self):
        """Load banner profiles from disk."""
        path = Path("configs/dj_banner_profiles.json")
        if not path.exists():
            return

        try:
            with open(path, "r") as f:
                profiles = json.load(f)

            banners_dir = Path("configs/banners").resolve()
            for dj_id, profile in profiles.items():
                if profile.get("has_image"):
                    safe_id = re.sub(r"[^a-zA-Z0-9_\-]", "", dj_id)
                    pixel_path = Path(f"configs/banners/{safe_id}_pixels.bin")
                    if not pixel_path.resolve().is_relative_to(banners_dir):
                        logger.warning("Blocked path traversal in banner for dj_id: %s", dj_id)
                        continue
                    if pixel_path.exists():
                        import struct

                        with open(pixel_path, "rb") as f:
                            data = f.read()
                        pixels = [
                            struct.unpack(">i", data[i : i + 4])[0] for i in range(0, len(data), 4)
                        ]
                        profile["image_pixels"] = pixels
                self._dj_banner_profiles[dj_id] = profile

            logger.info(f"Loaded {len(self._dj_banner_profiles)} banner profiles")
        except Exception as e:
            logger.warning(f"Failed to load banner profiles: {e}")

    def _save_banner_profiles(self):
        """Save banner profiles to disk."""
        path = Path("configs/dj_banner_profiles.json")
        path.parent.mkdir(parents=True, exist_ok=True)

        profiles_for_save = {}
        for dj_id, profile in self._dj_banner_profiles.items():
            save_profile = {k: v for k, v in profile.items() if k != "image_pixels"}
            if profile.get("image_pixels"):
                # Save pixel data as a separate binary file
                pixel_dir = Path("configs/banners")
                pixel_dir.mkdir(parents=True, exist_ok=True)
                pixel_path = pixel_dir / f"{dj_id}_pixels.bin"
                import struct

                try:
                    with open(pixel_path, "wb") as f:
                        for p in profile["image_pixels"]:
                            f.write(struct.pack(">i", p))
                    save_profile["has_image"] = True
                except Exception as e:
                    logger.warning(f"Failed to save pixel data for {dj_id}: {e}")
            profiles_for_save[dj_id] = save_profile

        try:
            with open(path, "w") as f:
                json.dump(profiles_for_save, f, indent=2)
        except Exception as e:
            logger.warning(f"Failed to save banner profiles: {e}")

    def _process_logo_image(
        self, image_base64: str, grid_width: int, grid_height: int
    ) -> Optional[List[int]]:
        """Downsample a PNG image to a pixel grid for TextDisplay rendering.

        Returns list of ARGB int values.
        """
        try:
            import base64
            import io

            from PIL import Image

            image_data = base64.b64decode(image_base64)
            img = Image.open(io.BytesIO(image_data))
            img = img.convert("RGBA")
            img = img.resize((grid_width, grid_height), Image.Resampling.LANCZOS)

            pixels = []
            for y in range(grid_height):
                for x in range(grid_width):
                    r, g, b, a = img.getpixel((x, y))
                    # Pack as ARGB int (Java Color.fromARGB format)
                    argb = (a << 24) | (r << 16) | (g << 8) | b
                    pixels.append(argb)

            return pixels
        except ImportError:
            logger.error("Pillow (PIL) required for logo processing: pip install Pillow")
            return None
        except Exception as e:
            logger.error(f"Failed to process logo image: {e}")
            return None

    # ------------------------------------------------------------------
    # Coordinator integration
    # ------------------------------------------------------------------

    async def _init_coordinator(self):
        """Register with the coordinator if configured."""
        cfg = ServerConfig.from_env()
        url = cfg.coordinator_url
        api_key = cfg.coordinator_api_key
        if not url or not api_key:
            logger.info(
                "Coordinator integration disabled (set COORDINATOR_URL and COORDINATOR_API_KEY to enable)"
            )
            return

        ws_url = cfg.coordinator_ws_url
        if not ws_url:
            ws_url = f"ws://localhost:{self.dj_port}"
            logger.warning(
                "COORDINATOR_WS_URL not set, using %s (DJs may not be able to reach this)", ws_url
            )

        server_name = cfg.coordinator_server_name

        self._coordinator = CoordinatorClient(url, api_key)
        try:
            await self._coordinator.register(server_name, ws_url)
            logger.info(
                "Coordinator integration active (server_id=%s)", self._coordinator.server_id
            )
            # Start periodic heartbeat
            self._coordinator_heartbeat_task = asyncio.create_task(
                self._coordinator_heartbeat_loop()
            )
        except Exception as exc:
            logger.error("Failed to register with coordinator: %s", exc)
            self._coordinator = None

    async def _coordinator_heartbeat_loop(self):
        """Send periodic heartbeats to the coordinator."""
        while True:
            await asyncio.sleep(120)  # every 2 minutes
            if self._coordinator:
                try:
                    await self._coordinator.heartbeat()
                except Exception as exc:
                    logger.warning("Coordinator heartbeat failed: %s", exc)

    async def _coordinator_create_show(self, ttl_minutes: int = 30) -> Optional[ConnectCode]:
        """Create a show on the coordinator and return a local ConnectCode with the same code."""
        if not self._coordinator:
            return None
        try:
            show_info = await self._coordinator.create_show(
                name="Live Show",
                max_djs=8,
            )
            # Create a local ConnectCode with the coordinator-generated code
            code = ConnectCode(
                code=show_info.connect_code,
                expires_at=time.time() + ttl_minutes * 60,
            )
            self._code_show_ids[show_info.connect_code] = show_info.show_id
            logger.info(
                "Created show on coordinator: code=%s show_id=%s",
                show_info.connect_code,
                show_info.show_id,
            )
            return code
        except Exception as exc:
            logger.error("Failed to create show on coordinator: %s", exc)
            return None

    def _cleanup_expired_codes(self):
        """Remove expired or used connect codes."""
        expired = [code for code, obj in self._connect_codes.items() if not obj.is_valid()]
        for code in expired:
            del self._connect_codes[code]
        if expired:
            logger.debug(f"Cleaned up {len(expired)} expired connect codes")

    async def _broadcast_connect_codes(self):
        """Broadcast active connect codes to all browser clients."""
        self._cleanup_expired_codes()
        codes = [
            {
                "code": code.code,
                "created_at": code.created_at,
                "expires_at": code.expires_at,
                "used": code.used,
            }
            for code in self._connect_codes.values()
            if code.is_valid()
        ]

        message = _json_str({"type": "connect_codes", "codes": codes})

        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients
