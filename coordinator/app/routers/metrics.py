"""Prometheus-style metrics endpoint."""

from __future__ import annotations

import re

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import PlainTextResponse

from app.config import Settings, get_settings
from app.services.metrics import snapshot as metrics_snapshot
from app.services.metrics import snapshot_http_latency

router = APIRouter(tags=["metrics"])

_SAFE_METRIC_RE = re.compile(r"[^a-zA-Z0-9_]")


def _to_prom_metric(name: str) -> str:
    cleaned = _SAFE_METRIC_RE.sub("_", name.replace(".", "_")).strip("_")
    return f"mcav_{cleaned}_total"


def _escape_label_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


async def _verify_metrics_token(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> None:
    """Check Bearer token on /metrics when metrics_token is configured and env != development."""
    if settings.metrics_token is None:
        return
    if settings.mcav_env.lower() == "development":
        return

    auth = request.headers.get("authorization", "")
    if not auth.startswith("Bearer ") or auth[7:] != settings.metrics_token:
        raise HTTPException(status_code=401, detail="Invalid or missing metrics token")


@router.get("/metrics", response_class=PlainTextResponse, summary="Prometheus metrics")
async def metrics_endpoint(
    _auth: None = Depends(_verify_metrics_token),
) -> PlainTextResponse:
    """Expose in-process counters and HTTP latency histograms in Prometheus
    text exposition format.
    """
    counters = metrics_snapshot()
    latencies = snapshot_http_latency()
    lines: list[str] = []
    for key in sorted(counters):
        metric_name = _to_prom_metric(key)
        lines.append(f"# HELP {metric_name} MCAV in-process counter for {key}.")
        lines.append(f"# TYPE {metric_name} counter")
        lines.append(f"{metric_name} {counters[key]}")
    if latencies:
        hist_name = "mcav_http_request_duration_ms"
        lines.append(f"# HELP {hist_name} HTTP request duration in milliseconds.")
        lines.append(f"# TYPE {hist_name} histogram")
        for method, path in sorted(latencies):
            data = latencies[(method, path)]
            buckets_ms = data["buckets_ms"]
            counts = data["counts"]
            cumulative = 0
            label_prefix = (
                f'method="{_escape_label_value(method)}",path="{_escape_label_value(path)}"'
            )
            for idx, upper in enumerate(buckets_ms):
                cumulative += int(counts[idx])
                lines.append(f'{hist_name}_bucket{{{label_prefix},le="{upper:g}"}} {cumulative}')
            lines.append(f'{hist_name}_bucket{{{label_prefix},le="+Inf"}} {int(data["count"])}')
            lines.append(f"{hist_name}_sum{{{label_prefix}}} {float(data['sum_ms']):.6f}")
            lines.append(f"{hist_name}_count{{{label_prefix}}} {int(data['count'])}")
    if not lines:
        lines.append("# No metrics recorded yet.")
    return PlainTextResponse("\n".join(lines) + "\n")
