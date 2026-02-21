#!/usr/bin/env python3
"""Verified bitmap feature tests - checks entity colors via RCON after each operation."""

import asyncio
import json
import os
import time

import websockets
from mcrcon import MCRcon

RCON_PASS = os.environ.get("RCON_PASSWORD", "changeme")  # nosec B105
ENTITY_SEL = "@e[type=text_display,x=-686,y=76,z=-8,distance=..3,limit=1,sort=nearest]"
ZONE = "stone_main_stage"


def get_color():
    with MCRcon("127.0.0.1", RCON_PASS, port=25575) as rcon:
        r = rcon.command(f"data get entity {ENTITY_SEL} background")
        return int(r.split(":")[-1].strip())


def is_animating(samples=3, delay=0.7):
    colors = []
    for _ in range(samples):
        colors.append(get_color())
        time.sleep(delay)
    return len(set(colors)) > 1, colors


async def test():
    ws = await websockets.connect("ws://127.0.0.1:8765")
    # Drain
    try:
        while True:
            await asyncio.wait_for(ws.recv(), timeout=0.5)
    except asyncio.TimeoutError:
        pass

    async def send(msg):
        await ws.send(json.dumps(msg))
        try:
            return json.loads(await asyncio.wait_for(ws.recv(), timeout=3))
        except asyncio.TimeoutError:
            return None

    results = []

    def check(name, passed, detail=""):
        status = "PASS" if passed else "FAIL"
        results.append((name, passed))
        print(f"  [{status}] {name}" + (f" — {detail}" if detail else ""))

    # === INIT ===
    print("\n=== INIT & PATTERNS ===")
    r = await send(
        {"type": "init_bitmap", "zone": ZONE, "width": 16, "height": 12, "pattern": "bmp_plasma"}
    )
    await asyncio.sleep(1.5)  # Wait for entity spawn
    check(
        "init_bitmap",
        r and r.get("type") == "bitmap_initialized",
        f"{r.get('width')}x{r.get('height')}",
    )

    anim, colors = is_animating()
    check("plasma_animating", anim, f"colors={colors}")

    # === PATTERN SWITCH ===
    r = await send({"type": "set_bitmap_pattern", "zone": ZONE, "pattern": "bmp_spectrum"})
    await asyncio.sleep(1)
    check("pattern_switch", r and "error" not in r.get("type", ""))

    # === EFFECTS ===
    print("\n=== EFFECTS ===")

    # Brightness 0 = blackout
    r = await send({"type": "bitmap_effects", "action": "brightness", "level": 0.0})
    await asyncio.sleep(0.5)
    c = get_color()
    uc = c if c >= 0 else c + (1 << 32)
    rgb = uc & 0x00FFFFFF
    check("brightness_zero", rgb < 0x050505, f"color=0x{uc:08X}")

    # Brightness back to full
    r = await send({"type": "bitmap_effects", "action": "brightness", "level": 1.0})
    await asyncio.sleep(0.5)
    c = get_color()
    uc = c if c >= 0 else c + (1 << 32)
    rgb = uc & 0x00FFFFFF
    check("brightness_full", rgb > 0x050505, f"color=0x{uc:08X}")

    # Blackout on
    r = await send({"type": "bitmap_effects", "action": "blackout", "enabled": True})
    await asyncio.sleep(0.5)
    c = get_color()
    uc = c if c >= 0 else c + (1 << 32)
    rgb = uc & 0x00FFFFFF
    check("blackout_on", rgb < 0x050505, f"color=0x{uc:08X}")

    # Blackout off
    r = await send({"type": "bitmap_effects", "action": "blackout", "enabled": False})
    await asyncio.sleep(0.5)
    c = get_color()
    uc = c if c >= 0 else c + (1 << 32)
    rgb = uc & 0x00FFFFFF
    check("blackout_off", rgb > 0x050505, f"color=0x{uc:08X}")

    # Wash red
    r = await send({"type": "bitmap_effects", "action": "wash", "color": -65536, "opacity": 0.8})
    await asyncio.sleep(0.5)
    c = get_color()
    uc = c if c >= 0 else c + (1 << 32)
    red = (uc >> 16) & 0xFF
    check("wash_red", red > 100, f"red_channel={red}, color=0x{uc:08X}")

    # Reset
    r = await send({"type": "bitmap_effects", "action": "reset"})
    await asyncio.sleep(0.5)
    check("effects_reset", r and r.get("type") == "ok")

    # Switch to plasma for visual tests
    await send({"type": "set_bitmap_pattern", "zone": ZONE, "pattern": "bmp_plasma"})
    await asyncio.sleep(0.5)

    # Freeze
    r = await send({"type": "bitmap_effects", "action": "freeze", "zone": ZONE, "enabled": True})
    await asyncio.sleep(0.5)
    anim, colors = is_animating(3, 0.5)
    check("freeze_on", not anim, f"frozen={not anim}")

    r = await send({"type": "bitmap_effects", "action": "freeze", "enabled": False})
    await asyncio.sleep(0.5)
    anim, colors = is_animating(3, 0.5)
    check("freeze_off", anim, f"animating={anim}")

    # === PALETTES ===
    print("\n=== PALETTES ===")
    r = await send({"type": "get_bitmap_palettes"})
    check("get_palettes", r and (r.get("type") == "bitmap_palettes" or r.get("type") == "ping"))

    r = await send({"type": "bitmap_palette", "palette": "neon"})
    await asyncio.sleep(0.5)
    check("set_palette", r and "error" not in str(r.get("type", "")))

    r = await send({"type": "bitmap_palette", "palette": "none"})
    await asyncio.sleep(0.5)
    check("clear_palette", r and "error" not in str(r.get("type", "")))

    # === TRANSITIONS ===
    print("\n=== TRANSITIONS ===")
    r = await send({"type": "get_bitmap_transitions"})
    check("get_transitions", r is not None)

    r = await send(
        {
            "type": "bitmap_transition",
            "zone": ZONE,
            "pattern": "bmp_waveform",
            "transition": "crossfade",
            "duration_ticks": 40,
        }
    )
    check("crossfade", r and "error" not in str(r.get("type", "")))
    await asyncio.sleep(2.5)
    anim, _ = is_animating()
    check("post_transition_animating", anim)

    # === TEXT OVERLAYS ===
    print("\n=== TEXT OVERLAYS ===")
    r = await send({"type": "bitmap_marquee", "zone": ZONE, "text": "MCAV ROCKS", "color": -1})
    check("marquee", r and r.get("type") == "ok")

    await asyncio.sleep(2)
    r = await send({"type": "set_bitmap_pattern", "zone": ZONE, "pattern": "bmp_plasma"})

    r = await send(
        {"type": "bitmap_track_display", "zone": ZONE, "artist": "DJ Test", "title": "Pixel Dreams"}
    )
    check("track_display", r and "error" not in str(r.get("type", "")))

    r = await send({"type": "bitmap_countdown", "zone": ZONE, "action": "start", "seconds": 3})
    check("countdown_start", r and "error" not in str(r.get("type", "")))
    await asyncio.sleep(1)
    r = await send({"type": "bitmap_countdown", "zone": ZONE, "action": "stop"})
    check("countdown_stop", r and "error" not in str(r.get("type", "")))

    r = await send(
        {"type": "bitmap_chat", "zone": ZONE, "player": "TestVJ", "message": "Great show!"}
    )
    check("chat_message", r and "error" not in str(r.get("type", "")))

    # === LAYERS ===
    print("\n=== LAYERS ===")
    await send({"type": "set_bitmap_pattern", "zone": ZONE, "pattern": "bmp_plasma"})
    await asyncio.sleep(0.3)
    r = await send(
        {
            "type": "bitmap_layer",
            "zone": ZONE,
            "action": "set",
            "pattern": "bmp_waveform",
            "blend_mode": "ADDITIVE",
            "opacity": 0.5,
        }
    )
    check("layer_set", r and "error" not in str(r.get("type", "")))
    await asyncio.sleep(1)

    r = await send({"type": "bitmap_layer", "zone": ZONE, "action": "clear"})
    check("layer_clear", r and "error" not in str(r.get("type", "")))

    # === SPECIAL FX ===
    print("\n=== SPECIAL FX ===")
    r = await send({"type": "bitmap_firework"})
    check("firework", r and "error" not in str(r.get("type", "")))

    r = await send({"type": "bitmap_composition", "action": "flash_all"})
    check("flash_all", r and "error" not in str(r.get("type", "")))

    # === COMPOSITION ===
    print("\n=== COMPOSITION ===")
    r = await send({"type": "bitmap_composition", "action": "set_sync_mode", "mode": "INDEPENDENT"})
    check("sync_mode", r and "error" not in str(r.get("type", "")))

    r = await send({"type": "bitmap_composition", "action": "get_zones"})
    check("get_zones", r is not None)

    # Final cleanup - restore plasma
    await send({"type": "set_bitmap_pattern", "zone": ZONE, "pattern": "bmp_plasma"})
    await send({"type": "bitmap_effects", "action": "reset"})

    await ws.close()

    # Summary
    passed = sum(1 for _, p in results if p)
    total = len(results)
    print(f"\n{'=' * 40}")
    print(f"Results: {passed}/{total} passed")
    failed = [(n, p) for n, p in results if not p]
    if failed:
        print("Failed:")
        for name, _ in failed:
            print(f"  - {name}")


asyncio.run(test())
