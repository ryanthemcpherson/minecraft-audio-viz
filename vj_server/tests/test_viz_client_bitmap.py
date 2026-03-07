#!/usr/bin/env python3
"""
Unit tests for VizClient bitmap protocol methods.

Tests message construction and parameter handling without a live WebSocket
server. Uses a mock to capture outgoing messages and return canned responses.
"""

import json

import pytest

from vj_server.viz_client import VizClient


class MockWebSocket:
    """Captures sent messages and returns canned responses."""

    def __init__(self):
        self.sent_messages: list[dict] = []
        self._response_queue: list[dict] = []

    def queue_response(self, resp: dict):
        self._response_queue.append(resp)

    async def send(self, data: str):
        msg = json.loads(data)
        self.sent_messages.append(msg)

    async def recv(self):
        if self._response_queue:
            return json.dumps(self._response_queue.pop(0))
        # Default: return ok
        return json.dumps({"type": "ok"})

    @property
    def open(self):
        return True

    async def close(self):
        pass


@pytest.fixture
def client_and_ws():
    """Create a VizClient with a mocked WebSocket connection."""
    client = VizClient(host="localhost", port=8765)
    mock_ws = MockWebSocket()
    client.ws = mock_ws
    client._connected = True
    client._use_receive_loop = False
    return client, mock_ws


def last_sent(ws: MockWebSocket) -> dict:
    """Get the last message sent through the websocket."""
    assert ws.sent_messages, "No messages were sent"
    return ws.sent_messages[-1]


# ========== Transition Methods ==========


class TestBitmapTransition:
    @pytest.mark.asyncio
    async def test_transition_message_structure(self, client_and_ws):
        client, ws = client_and_ws
        ws.queue_response({"type": "bitmap_transition_started"})

        await client.bitmap_transition("main", "bmp_plasma", "crossfade", 20)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_transition"
        assert msg["zone"] == "main"
        assert msg["pattern"] == "bmp_plasma"
        assert msg["transition"] == "crossfade"
        assert msg["duration_ticks"] == 20

    @pytest.mark.asyncio
    async def test_get_transitions(self, client_and_ws):
        client, ws = client_and_ws
        ws.queue_response({"type": "bitmap_transitions", "transitions": []})

        await client.get_bitmap_transitions()

        msg = last_sent(ws)
        assert msg["type"] == "get_bitmap_transitions"


# ========== Text Methods ==========


class TestBitmapText:
    @pytest.mark.asyncio
    async def test_marquee_message(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_marquee("main", "HELLO WORLD", color=0xFFFF0000, speed=2.0)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_marquee"
        assert msg["zone"] == "main"
        assert msg["text"] == "HELLO WORLD"
        assert msg["color"] == 0xFFFF0000
        assert msg["speed"] == 2.0

    @pytest.mark.asyncio
    async def test_track_display(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_track_display("main", "Deadmau5", "Strobe")

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_track_display"
        assert msg["zone"] == "main"
        assert msg["artist"] == "Deadmau5"
        assert msg["title"] == "Strobe"

    @pytest.mark.asyncio
    async def test_countdown_start(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_countdown("main", 30, action="start")

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_countdown"
        assert msg["zone"] == "main"
        assert msg["seconds"] == 30
        assert msg["action"] == "start"

    @pytest.mark.asyncio
    async def test_chat_message_field(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_chat("main", "Steve", "GG!")

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_chat"
        assert msg["zone"] == "main"
        assert msg["player"] == "Steve"
        assert msg["message"] == "GG!"


# ========== Effects Methods ==========


class TestBitmapEffects:
    @pytest.mark.asyncio
    async def test_strobe(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_strobe(enabled=True, divisor=4, color=0xFFFFFFFF)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_effects"
        assert msg["action"] == "strobe"
        assert msg["enabled"] is True
        assert msg["divisor"] == 4
        assert msg["color"] == 0xFFFFFFFF

    @pytest.mark.asyncio
    async def test_brightness(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_brightness(0.7)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_effects"
        assert msg["action"] == "brightness"
        assert msg["level"] == 0.7

    @pytest.mark.asyncio
    async def test_blackout(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_blackout(True)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_effects"
        assert msg["action"] == "blackout"
        assert msg["enabled"] is True

    @pytest.mark.asyncio
    async def test_wash(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_wash(0xFFFF0000, opacity=0.5)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_effects"
        assert msg["action"] == "wash"
        assert msg["color"] == 0xFFFF0000
        assert msg["opacity"] == 0.5

    @pytest.mark.asyncio
    async def test_freeze(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_freeze("main", True)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_effects"
        assert msg["action"] == "freeze"
        assert msg["zone"] == "main"
        assert msg["enabled"] is True


# ========== Palette Methods ==========


class TestBitmapPalette:
    @pytest.mark.asyncio
    async def test_set_palette(self, client_and_ws):
        client, ws = client_and_ws
        ws.queue_response({"type": "bitmap_palette_set", "palette": "neon"})

        await client.bitmap_palette("neon")

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_palette"
        assert msg["palette"] == "neon"

    @pytest.mark.asyncio
    async def test_get_palettes(self, client_and_ws):
        client, ws = client_and_ws
        ws.queue_response({"type": "bitmap_palettes", "palettes": []})

        await client.get_bitmap_palettes()

        msg = last_sent(ws)
        assert msg["type"] == "get_bitmap_palettes"


# ========== Firework / Layer / Image ==========


class TestBitmapMisc:
    @pytest.mark.asyncio
    async def test_firework(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_firework("main", x=0.5, y=0.3)

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_firework"
        assert msg["x"] == 0.5
        assert msg["y"] == 0.3

    @pytest.mark.asyncio
    async def test_layer_set(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_layer(
            "main", action="set", pattern="bmp_plasma", blend_mode="ADDITIVE", opacity=0.5
        )

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_layer"
        assert msg["zone"] == "main"
        assert msg["action"] == "set"
        assert msg["pattern"] == "bmp_plasma"
        assert msg["blend_mode"] == "ADDITIVE"
        assert msg["opacity"] == 0.5

    @pytest.mark.asyncio
    async def test_image_clear(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_image("main", action="clear")

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_image"
        assert msg["zone"] == "main"
        assert msg["action"] == "clear"


# ========== Composition ==========


class TestBitmapComposition:
    @pytest.mark.asyncio
    async def test_composition_sync(self, client_and_ws):
        client, ws = client_and_ws

        await client.bitmap_composition(action="sync", mode="beat")

        msg = last_sent(ws)
        assert msg["type"] == "bitmap_composition"
        assert msg["action"] == "sync"
        assert msg["mode"] == "beat"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
