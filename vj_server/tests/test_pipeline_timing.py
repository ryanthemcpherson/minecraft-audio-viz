"""Clock-domain, optional-wire-field and real handler/selection regressions."""

import json
import time
from pathlib import Path
from unittest.mock import Mock

import pytest

from vj_server.models import DJConnection, _frame_decoder, _sanitize_audio_frame
from vj_server.pipeline_timing import (
    DJ_FIELDS,
    MAX_DURATION_MS,
    STAGES,
    WINDOW_SIZE,
    PipelineTimingMetrics,
    sanitize_timing,
)
from vj_server.vj_server import VJServer

TIMING = {
    "buffer_to_analysis_ms": 7.0,
    "analysis_ms": 2.0,
    "analysis_to_enqueue_ms": 7.0,
    "window_ms": 1024 / 48,
}


@pytest.mark.parametrize("value", [None, [], "bad", 42, {}, {**TIMING, "unknown": 1}])
def test_invalid_metadata_does_not_reject_audio(value):
    frame = {"type": "dj_audio_frame", "bands": [0.5] * 5, "timing": value}
    decoded = _frame_decoder.decode(json.dumps(frame).encode())
    safe = _sanitize_audio_frame(decoded)
    assert safe["bands"] == [0.5] * 5
    assert safe["timing"] is None


@pytest.mark.parametrize("field", DJ_FIELDS)
@pytest.mark.parametrize("value", [-1, 60001, True, "1", None, float("inf"), float("nan"), 10**400])
def test_invalid_numbers_are_omitted_not_clamped(field, value):
    assert sanitize_timing({**TIMING, field: value}) is None


def test_boundaries_and_schema_agree():
    schema_path = (
        Path(__file__).resolve().parents[2] / "protocol/schemas/messages/dj-audio-frame.schema.json"
    )
    schema = json.loads(schema_path.read_text())
    assert "timing" not in schema["required"]
    timing_schema = schema["properties"]["timing"]
    assert timing_schema["additionalProperties"] is False
    assert set(timing_schema["required"]) == set(DJ_FIELDS)
    for field in DJ_FIELDS:
        definition = timing_schema["properties"][field]
        assert definition["type"] == "number"
        assert definition["maximum"] == MAX_DURATION_MS
        assert sanitize_timing({**TIMING, field: MAX_DURATION_MS}) is not None
        if field == "window_ms":
            assert definition["exclusiveMinimum"] == 0
            assert sanitize_timing({**TIMING, field: 0}) is None
        else:
            assert definition["minimum"] == 0
            assert sanitize_timing({**TIMING, field: 0}) is not None


def test_old_and_new_wire_frames_have_identical_audio():
    frame = {"type": "dj_audio_frame", "bands": [0.1, 0.2, 0.3, 0.4, 0.5], "beat": True}
    legacy = _sanitize_audio_frame(_frame_decoder.decode(json.dumps(frame).encode()))
    extended = _sanitize_audio_frame(
        _frame_decoder.decode(json.dumps({**frame, "timing": TIMING}).encode())
    )
    assert legacy.pop("timing") is None
    assert extended.pop("timing") == TIMING
    assert legacy == extended


def test_metrics_are_bounded_and_absent_values_are_not_zero():
    metrics = PipelineTimingMetrics()
    assert all(line.startswith("#") for line in metrics.prometheus_lines())
    metrics.observe_dj(None)
    for index in range(WINDOW_SIZE + 20):
        metrics.observe("server_handler_ms", index)
    metrics.observe("server_handler_ms", float("nan"))
    metrics.observe("server_handler_ms", -1)
    assert set(metrics.samples) == set(STAGES)
    assert len(metrics.samples["server_handler_ms"]) == WINDOW_SIZE
    assert metrics.samples["server_handler_ms"][0] == 20
    output = "\n".join(metrics.prometheus_lines())
    assert 'stage="dj_' not in output
    assert 'statistic="p50"} 275.000000' in output
    assert 'statistic="p95"} 506.000000' in output
    assert 'statistic="max"} 531.000000' in output


@pytest.fixture
def server():
    instance = VJServer.__new__(VJServer)
    instance._pipeline_timing = PipelineTimingMetrics()
    instance._visual_delay_mode = "manual"
    instance._visual_delay_ms = 0.0
    instance._active_dj_id = "dj"
    instance._beat_predictor = Mock()
    return instance


@pytest.mark.asyncio
async def test_handler_and_delayed_selection_use_matching_local_receipt(server, monkeypatch):
    dj = DJConnection("dj", "DJ", Mock())
    dj.check_rate_limit = Mock(return_value=True)
    ticks = iter([50.0, 50.002, 50.016, 50.019, 50.025, 50.032, 50.040])
    clock = Mock(wraps=time)
    clock.monotonic.side_effect = lambda: next(ticks)
    clock.time.return_value = 1_800_000_000.0
    monkeypatch.setattr("vj_server.vj_server.time", clock)
    first = {"type": "dj_audio_frame", "seq": 1, "bands": [0.1] * 5, "timing": TIMING}
    await server._handle_dj_frame(dj, first)
    # Epoch clocks can disagree arbitrarily: new metrics must remain unchanged.
    clock.time.return_value = 1_800_000_000.016
    await server._handle_dj_frame(dj, {**first, "seq": 2, "bands": [0.9] * 5, "ts": -999999.0})
    delayed = server._read_delayed_frame(dj, 16)
    assert delayed["bands"] == [0.1] * 5
    assert delayed["_received_mono"] == 50.0
    assert dj.last_frame_received_mono == 50.016
    server._observe_frame_selection(dj, delayed)
    server._observe_frame_selection(dj, None)
    server._observe_frame_selection(dj, delayed)
    assert list(server._pipeline_timing.samples["server_handler_ms"]) == pytest.approx([2, 3])
    assert list(server._pipeline_timing.samples["server_receipt_to_selection_ms"]) == pytest.approx(
        [25, 16, 40]
    )
    assert list(server._pipeline_timing.samples["dj_analysis_ms"]) == [2, 2]


@pytest.mark.asyncio
async def test_dropped_and_missing_frames_do_not_create_observations(server):
    dj = DJConnection("dj", "DJ", Mock())
    dj.check_rate_limit = Mock(return_value=False)
    await server._handle_dj_frame(dj, {"timing": TIMING})
    server._observe_frame_selection(dj, None)
    server._observe_frame_selection(dj, {"bands": [0] * 5})
    assert dj.last_frame_received_mono is None
    assert not any(server._pipeline_timing.samples.values())
