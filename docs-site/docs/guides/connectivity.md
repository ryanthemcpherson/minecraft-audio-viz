# WebSocket Connectivity

This document describes the WebSocket connectivity architecture for MCAV, including all ports, reconnection behavior, heartbeat mechanisms, and troubleshooting guidance.

## Architecture Overview

The system uses a three-tier distributed architecture with WebSocket connections:

```text
Audio Sources (Windows)
  WASAPI Audio Capture (Spotify, Chrome, etc.)
         |
         v
Audio Processor (DJ Client / VJ Server)
  FFT Analyzer --> Beat Detection --> Pattern Engine
         |
  +------+------+-------------------+
  v             v                   v
Minecraft    Browser Clients     Remote DJs
Plugin       (Admin + Preview)   (DJ Client)
(:8765)      (:8766, :8080)      (:9000)
```

## Port Reference

| Port | Protocol | Component | Description |
|------|----------|-----------|-------------|
| **8765** | WebSocket | Minecraft Plugin | Primary visualization data channel |
| **8766** | WebSocket | VJ Server to Browsers | Browser client broadcast (preview + admin) |
| **9000** | WebSocket | VJ Server from DJs | Remote DJ audio frame connections |
| **8080** | HTTP | VJ Server | Admin panel web interface |

## Reconnection Behavior

All WebSocket connections implement exponential backoff with jitter to prevent thundering herd problems.

### DJ Relay (Remote DJ to VJ Server)

| Parameter | Value | Description |
|-----------|-------|-------------|
| Initial backoff | 5s (default) | Starting delay between reconnection attempts |
| Backoff multiplier | 1.5x | Each failure multiplies the delay |
| Maximum backoff | 30s | Delay is capped at this value |
| Jitter | +/-10% | Random variation to prevent synchronized reconnects |
| Max connect attempts | 3 (default) | Initial connection retries before failure |

**Behavior:**

- On initial connection: Retries up to `max_connect_attempts` times with exponential backoff
- On connection loss: Automatically reconnects with exponential backoff (resets on success)
- On auth failure: Does NOT retry (credentials are wrong)

### VJ Server to Minecraft

| Parameter | Value | Description |
|-----------|-------|-------------|
| Check interval | 5s | How often connection health is checked |
| Initial backoff | 5s | Starting delay after disconnect detected |
| Backoff multiplier | 1.5x | Each failure multiplies the delay |
| Maximum backoff | 60s | Delay is capped at this value |

### Admin Panel (Browser to VJ Server)

| Parameter | Value | Description |
|-----------|-------|-------------|
| Initial backoff | 2s | Starting delay |
| Backoff multiplier | 1.5x | Each failure multiplies the delay |
| Maximum backoff | 30s | Delay is capped at this value |
| Max attempts | 10 | Total reconnection attempts before giving up |
| Ping interval | 5s | How often client pings the server |
| Pong timeout | 20s | Time without pong before reconnecting |
| Message queue size | 500 | Max queued messages while disconnected (FIFO eviction) |

### Python VizClient

| Parameter | Default | Description |
|-----------|---------|-------------|
| Connect timeout | 10s | Timeout for initial connection |
| Auto-reconnect | False | Enable automatic reconnection (opt-in) |
| Initial backoff | 1s | Starting delay |
| Backoff multiplier | 1.5x | Each failure multiplies the delay |
| Maximum backoff | 30s | Delay is capped at this value |
| Max attempts | 10 | Total reconnection attempts |

## Heartbeat Mechanisms

Heartbeats detect dead connections that TCP keep-alive might miss.

### DJ to VJ Server Heartbeat

| Parameter | Value |
|-----------|-------|
| Interval | 2s |
| Failure threshold | 3 consecutive |

```json
// DJ sends:
{"type": "dj_heartbeat", "ts": 1706889600.123, "mc_connected": true}

// VJ Server responds:
{"type": "heartbeat_ack", "server_time": 1706889600.125}
```

### VizClient to Minecraft Heartbeat

| Parameter | Value |
|-----------|-------|
| Ping interval | 10s |
| Pong timeout | 30s |

### VJ Server to Browser Heartbeat

| Parameter | Value |
|-----------|-------|
| Ping interval | 15s |
| Missed pong threshold | 2 |

## Configuration

### DJRelayConfig (Python)

```python
from audio_processor.dj_relay import DJRelayConfig

config = DJRelayConfig(
    vj_server_url="ws://vj-server.example.com:9000",
    dj_id="dj_alice",
    dj_name="DJ Alice",
    dj_key="secret_key",
    reconnect_interval=5.0,
    heartbeat_interval=2.0,
    max_connect_attempts=3,
)
```

### VizClient (Python)

```python
from python_client import VizClient

client = VizClient(
    host="localhost",
    port=8765,
    connect_timeout=10.0,
    auto_reconnect=False,
    max_reconnect_attempts=10,
)
```

## Network Tuning

### LAN Deployment (Low Latency)

```python
# DJ Relay
config = DJRelayConfig(
    reconnect_interval=2.0,
    heartbeat_interval=1.0,
    max_connect_attempts=5,
)

# VizClient
client = VizClient(connect_timeout=5.0, auto_reconnect=True)
```

### WAN Deployment (High Latency)

```python
# DJ Relay
config = DJRelayConfig(
    reconnect_interval=10.0,
    heartbeat_interval=5.0,
    max_connect_attempts=3,
)

# VizClient
client = VizClient(
    connect_timeout=30.0,
    auto_reconnect=True,
    max_reconnect_attempts=15,
)
```

## Troubleshooting

### Connection Refused

**Symptoms:** `ConnectionRefusedError` in logs

**Causes:** Target service not running, firewall blocking port, wrong host/port.

**Solutions:** Verify service is running (`netstat -an | grep <port>`), check firewall rules, verify configuration.

### Connection Timeout

**Symptoms:** Connection hangs then fails

**Causes:** Network routing issues, host unreachable, port silently dropped by firewall.

**Solutions:** Ping the host, check intermediate firewalls, increase `connect_timeout`.

### Heartbeat Timeout

**Symptoms:** "Pong timeout" or "Heartbeat failures exceeded" in logs

**Causes:** Network congestion, server overload, half-open connection.

**Solutions:** Check server load, increase heartbeat intervals, check for network quality issues.

### Message Queue Overflow

**Symptoms:** "Queue full" warnings, dropped messages

**Causes:** Processing too slow, burst of messages, slow server tick rate.

**Solutions:** Reduce message frequency (lower FPS), increase queue size, check processing bottlenecks.

## Health Monitoring

The VJ Server provides health statistics:

```json
{
    "dj_connects": 15,
    "dj_disconnects": 12,
    "browser_connects": 45,
    "browser_disconnects": 42,
    "mc_reconnect_count": 2,
    "current_djs": 3,
    "current_browsers": 3,
    "mc_connected": true
}
```

These metrics are logged every 60 seconds.
