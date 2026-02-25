"""Tests for reconnect-time stage config rehydration."""

from __future__ import annotations

import pytest

from vj_server.vj_server import VJServer


class _FakeVizClient:
    def __init__(self, response: dict):
        self.connected = True
        self._response = response

    async def send(self, _message: dict) -> dict:
        return self._response


@pytest.mark.asyncio
async def test_rehydrate_applies_block_zone_pattern_and_entity_count() -> None:
    server = VJServer(require_auth=False, show_spectrograph=False)
    server.viz_client = _FakeVizClient(
        {
            "type": "stages",
            "stages": [
                {
                    "name": "Main Stage",
                    "active": True,
                    "zones": {
                        "CENTER": {
                            "zone_name": "main",
                            "entity_count": 16,
                            "config": {
                                "pattern": "wave",
                                "entity_count": 64,
                                "render_mode": "block",
                                "block_type": "DIAMOND_BLOCK",
                            },
                        }
                    },
                }
            ],
        }
    )

    applied = await server._rehydrate_zone_states_from_active_stage()

    assert applied is True
    zs = server._get_zone_state("main")
    assert zs.render_mode == "block"
    assert zs.pattern_name == "wave"
    assert zs.entity_count == 64
    assert zs.block_type == "DIAMOND_BLOCK"


@pytest.mark.asyncio
async def test_rehydrate_applies_bitmap_mode_from_active_stage() -> None:
    server = VJServer(require_auth=False, show_spectrograph=False)
    server.viz_client = _FakeVizClient(
        {
            "type": "stages",
            "stages": [
                {
                    "name": "Main Stage",
                    "active": True,
                    "zones": {
                        "WALL": {
                            "zone_name": "led_wall",
                            "entity_count": 128,
                            "config": {
                                "pattern": "bmp_plasma",
                                "entity_count": 200,
                                "render_mode": "bitmap",
                            },
                        }
                    },
                }
            ],
        }
    )

    applied = await server._rehydrate_zone_states_from_active_stage()

    assert applied is True
    zs = server._get_zone_state("led_wall")
    assert zs.render_mode == "bitmap"
    assert zs.pattern_name == "bmp_plasma"
    assert zs.entity_count == 200


@pytest.mark.asyncio
async def test_rehydrate_no_active_stage_is_noop() -> None:
    server = VJServer(require_auth=False, show_spectrograph=False)
    server.viz_client = _FakeVizClient({"type": "stages", "stages": [{"name": "x", "active": False}]})

    applied = await server._rehydrate_zone_states_from_active_stage()

    assert applied is False
