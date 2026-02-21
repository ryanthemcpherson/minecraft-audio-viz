#!/usr/bin/env python3
"""Test all bitmap LED wall features via WebSocket."""

import asyncio
import json

import websockets


async def test():
    # Connect directly to Minecraft plugin (port 8765) to bypass VJ server heartbeat
    uri = "ws://127.0.0.1:8765"
    async with websockets.connect(uri) as ws:
        # Drain initial messages (connected, state, etc.)
        try:
            while True:
                msg = await asyncio.wait_for(ws.recv(), timeout=1)
                data = json.loads(msg)
                print(f"  [init] {data.get('type', '?')}")
        except asyncio.TimeoutError:
            pass

        async def send_and_recv(msg, label, timeout=3):
            await ws.send(json.dumps(msg))
            try:
                resp = await asyncio.wait_for(ws.recv(), timeout=timeout)
                data = json.loads(resp)
                rtype = data.get("type", "?")
                if rtype == "error":
                    err = data.get("message", "?")
                    print(f"  FAIL {label}: {err}")
                    return data
                else:
                    print(f"  OK   {label}: type={rtype}")
                    return data
            except asyncio.TimeoutError:
                print(f"  TIMEOUT {label}")
                return None

        # 1. Init bitmap
        print("=== INIT ===")
        r = await send_and_recv(
            {
                "type": "init_bitmap",
                "zone": "stone_main_stage",
                "width": 16,
                "height": 12,
                "pattern": "bmp_plasma",
            },
            "init_bitmap",
        )
        await asyncio.sleep(1)

        # 2. Get patterns
        print("\n=== PATTERNS ===")
        r = await send_and_recv({"type": "get_bitmap_patterns"}, "get_patterns")
        if r and r.get("type") == "bitmap_patterns":
            ids = [p["id"] for p in r.get("patterns", [])]
            print(f"  Patterns: {ids}")

        # 3. Switch pattern (instant)
        r = await send_and_recv(
            {"type": "set_bitmap_pattern", "zone": "stone_main_stage", "pattern": "bmp_spectrum"},
            "set_pattern_instant",
        )

        # 4. Get transitions
        print("\n=== TRANSITIONS ===")
        r = await send_and_recv({"type": "get_bitmap_transitions"}, "get_transitions")
        if r and r.get("type") == "bitmap_transitions":
            ids = [t["id"] for t in r.get("transitions", [])]
            print(f"  Transitions: {ids}")

        # 5. Transition to plasma
        r = await send_and_recv(
            {
                "type": "bitmap_transition",
                "zone": "stone_main_stage",
                "pattern": "bmp_plasma",
                "transition": "crossfade",
                "duration_ticks": 40,
            },
            "crossfade_transition",
        )

        # 6. Get palettes
        print("\n=== PALETTES ===")
        r = await send_and_recv({"type": "get_bitmap_palettes"}, "get_palettes")
        if r and r.get("type") == "bitmap_palettes":
            ids = [p["id"] for p in r.get("palettes", [])]
            print(f"  Palettes: {ids}")

        # 7. Set palette
        r = await send_and_recv({"type": "bitmap_palette", "palette": "neon"}, "set_palette_neon")
        await asyncio.sleep(0.5)
        r = await send_and_recv({"type": "bitmap_palette", "palette": "none"}, "clear_palette")

        # 8. Effects
        print("\n=== EFFECTS ===")
        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "brightness", "level": 0.5}, "brightness_50"
        )
        await asyncio.sleep(0.5)
        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "brightness", "level": 1.0}, "brightness_100"
        )

        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "strobe", "enabled": True}, "strobe_on"
        )
        await asyncio.sleep(1)
        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "strobe", "enabled": False}, "strobe_off"
        )

        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "blackout", "enabled": True}, "blackout_on"
        )
        await asyncio.sleep(0.5)
        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "blackout", "enabled": False}, "blackout_off"
        )

        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "wash", "color": -65536, "opacity": 0.5},
            "wash_red",
        )
        await asyncio.sleep(0.5)
        r = await send_and_recv({"type": "bitmap_effects", "action": "reset"}, "effects_reset")

        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "beat_flash", "enabled": True}, "beat_flash_on"
        )
        await asyncio.sleep(0.5)
        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "beat_flash", "enabled": False}, "beat_flash_off"
        )

        r = await send_and_recv(
            {
                "type": "bitmap_effects",
                "action": "freeze",
                "zone": "stone_main_stage",
                "enabled": True,
            },
            "freeze_on",
        )
        await asyncio.sleep(1)
        r = await send_and_recv(
            {"type": "bitmap_effects", "action": "freeze", "enabled": False}, "freeze_off"
        )

        # 9. Text overlays
        print("\n=== TEXT OVERLAYS ===")
        r = await send_and_recv(
            {
                "type": "bitmap_marquee",
                "zone": "stone_main_stage",
                "text": "Hello MCAV!",
                "color": -1,
            },
            "marquee",
        )
        await asyncio.sleep(2)

        r = await send_and_recv(
            {"type": "set_bitmap_pattern", "zone": "stone_main_stage", "pattern": "bmp_plasma"},
            "restore_plasma",
        )

        r = await send_and_recv(
            {
                "type": "bitmap_track_display",
                "zone": "stone_main_stage",
                "artist": "DJ Test",
                "title": "Pixel Dreams",
            },
            "track_display",
        )
        await asyncio.sleep(1)

        r = await send_and_recv(
            {
                "type": "bitmap_countdown",
                "zone": "stone_main_stage",
                "action": "start",
                "seconds": 5,
            },
            "countdown_start",
        )
        await asyncio.sleep(2)
        r = await send_and_recv(
            {"type": "bitmap_countdown", "zone": "stone_main_stage", "action": "stop"},
            "countdown_stop",
        )

        r = await send_and_recv(
            {
                "type": "bitmap_chat",
                "zone": "stone_main_stage",
                "player": "TestVJ",
                "message": "Great show!",
            },
            "chat",
        )

        # 10. Layers
        print("\n=== LAYERS ===")
        r = await send_and_recv(
            {"type": "set_bitmap_pattern", "zone": "stone_main_stage", "pattern": "bmp_plasma"},
            "pre_layer_pattern",
        )
        r = await send_and_recv(
            {
                "type": "bitmap_layer",
                "zone": "stone_main_stage",
                "action": "set",
                "pattern": "bmp_waveform",
                "blend_mode": "ADDITIVE",
                "opacity": 0.5,
            },
            "layer_set",
        )
        await asyncio.sleep(1)
        r = await send_and_recv(
            {"type": "bitmap_layer", "zone": "stone_main_stage", "action": "clear"}, "layer_clear"
        )

        # 11. Special effects
        print("\n=== SPECIAL FX ===")
        r = await send_and_recv({"type": "bitmap_firework"}, "firework")
        r = await send_and_recv({"type": "bitmap_composition", "action": "flash_all"}, "flash_all")

        # 12. Composition
        print("\n=== COMPOSITION ===")
        r = await send_and_recv(
            {"type": "bitmap_composition", "action": "set_sync_mode", "mode": "INDEPENDENT"},
            "sync_independent",
        )
        r = await send_and_recv({"type": "bitmap_composition", "action": "get_zones"}, "get_zones")
        if r and r.get("type") == "bitmap_composition_zones":
            print(f"  Zones registered: {r.get('zones', [])}")

        print("\n=== ALL TESTS COMPLETE ===")


asyncio.run(test())
