# VJ Server Health Metrics

The VJ server includes a lightweight HTTP metrics endpoint for production monitoring and observability.

## Endpoints

### `GET /health`

Returns a JSON health check with current status:

```json
{
  "status": "ok",
  "uptime_seconds": 3600.0,
  "connected_djs": 2,
  "connected_browsers": 5,
  "active_pattern": "spectrum",
  "active_dj": "DJ_Name",
  "minecraft_connected": true
}
```

**Use case:** Load balancer health checks, service monitoring dashboards

### `GET /metrics`

Returns Prometheus-compatible text format metrics:

```
# HELP mcav_uptime_seconds Server uptime in seconds
# TYPE mcav_uptime_seconds gauge
mcav_uptime_seconds 3600.12

# HELP mcav_connected_djs Number of currently connected DJs
# TYPE mcav_connected_djs gauge
mcav_connected_djs 2

# HELP mcav_connected_browsers Number of currently connected browser clients
# TYPE mcav_connected_browsers gauge
mcav_connected_browsers 5

# HELP mcav_frames_processed_total Total audio frames processed
# TYPE mcav_frames_processed_total counter
mcav_frames_processed_total 216000

# HELP mcav_pattern_changes_total Total pattern changes
# TYPE mcav_pattern_changes_total counter
mcav_pattern_changes_total 8

# HELP mcav_dj_connections_total Total DJ connections since start
# TYPE mcav_dj_connections_total counter
mcav_dj_connections_total 15

# HELP mcav_current_bpm Current BPM from active DJ
# TYPE mcav_current_bpm gauge
mcav_current_bpm 128.5

# HELP mcav_active_pattern Currently active visualization pattern
# TYPE mcav_active_pattern gauge
mcav_active_pattern{pattern="spectrum"} 1
```

**Use case:** Prometheus scraping, Grafana dashboards, alerting

## Configuration

### CLI Arguments

```bash
# Start with metrics on default port (9001)
audioviz-vj

# Custom metrics port
audioviz-vj --metrics-port 9002

# Disable metrics endpoint
audioviz-vj --no-metrics
```

### Environment Variables

```bash
# Default metrics port
export METRICS_PORT=9001
audioviz-vj
```

## Implementation

- **Lightweight:** Uses asyncio's built-in `asyncio.start_server()` (no external dependencies)
- **Efficient:** Minimal overhead, no impact on visualization performance
- **Standard:** Prometheus text format 0.0.4 for metrics endpoint
- **Production-ready:** Safe for public exposure (read-only, no sensitive data)

## Metrics Tracking

The server automatically tracks:

- **Uptime:** Server start time tracked in `VJServer._start_time`
- **Frames processed:** Incremented in main visualization loop (`VJServer._frames_processed`)
- **Pattern changes:** Incremented when patterns switch (`VJServer._pattern_changes`)
- **Connection counts:** From existing health stats (`_dj_connects`, `_djs`, `_broadcast_clients`)
- **Current state:** Active pattern name, active DJ, BPM, Minecraft connection status

## Testing

Run the test suite to verify endpoints:

```bash
cd vj_server
python test_metrics.py
```

Expected output:
```
[OK] Metrics server started on http://127.0.0.1:9099
[OK] /health endpoint works
[OK] /metrics endpoint works
[OK] 404 handling works
[OK] All tests passed!
```

## Integration Examples

### Prometheus Scrape Config

```yaml
scrape_configs:
  - job_name: 'mcav-vj-server'
    static_configs:
      - targets: ['localhost:9001']
    scrape_interval: 15s
```

### Docker Health Check

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:9001/health || exit 1
```

### Kubernetes Liveness Probe

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 9001
  initialDelaySeconds: 10
  periodSeconds: 30
```

## Files

- `vj_server/metrics.py` - HTTP server and endpoint handlers
- `vj_server/test_metrics.py` - Test suite
- `vj_server/vj_server.py` - Metrics tracking integration
- `vj_server/cli.py` - CLI argument parsing
