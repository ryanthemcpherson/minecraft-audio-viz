# MCAV DJ Coordinator

Central coordination service for MCAV (Minecraft Audio Visualizer). Manages connect codes that DJs use to join VJ server shows.

## Quick Start

### Prerequisites

- Python 3.12+
- PostgreSQL (or SQLite for development via `aiosqlite`)

### Install

```bash
# Using UV (recommended)
uv pip install -e ".[dev]"

# Or pip
pip install -e ".[dev]"
```

### Run locally

```bash
# Set required env vars
export MCAV_DATABASE_URL="postgresql+asyncpg://postgres:postgres@localhost:5432/mcav"

# Run migrations
alembic upgrade head

# Start the server
uvicorn app.main:app --host 0.0.0.0 --port 8090 --reload
```

### Run tests

```bash
pytest
```

Tests use an in-memory SQLite database and require no external services.

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/health` | None | Health check |
| POST | `/api/v1/servers/register` | None | Register a VJ server |
| PUT | `/api/v1/servers/{id}/heartbeat` | Bearer API key | Update server heartbeat |
| POST | `/api/v1/shows` | Bearer API key | Create a show (generates connect code) |
| GET | `/api/v1/shows/{id}` | Bearer API key | Get show details |
| DELETE | `/api/v1/shows/{id}` | Bearer API key | End a show |
| GET | `/api/v1/connect/{code}` | None (rate-limited) | Resolve connect code to WebSocket URL + JWT |

## Environment Variables

All prefixed with `MCAV_`:

| Variable | Default | Description |
|----------|---------|-------------|
| `MCAV_DATABASE_URL` | `postgresql+asyncpg://postgres:postgres@localhost:5432/mcav` | Database connection URL |
| `MCAV_JWT_DEFAULT_EXPIRY_MINUTES` | `15` | JWT token lifetime |
| `MCAV_RATE_LIMIT_RESOLVE_PER_MINUTE` | `10` | Rate limit for /connect endpoint |
| `MCAV_CORS_ORIGINS` | `["https://mcav.live", "http://localhost:3000"]` | Allowed CORS origins |

## Docker

```bash
docker build -t mcav-coordinator .
docker run -p 8090:8090 -e MCAV_DATABASE_URL="..." mcav-coordinator
```
