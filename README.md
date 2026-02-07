# mcav-site

Landing site and DJ coordinator backend for [MCAV](https://mcav.live) — real-time audio visualization in Minecraft.

## Architecture

```
mcav-site/
  site/          → Next.js landing page (mcav.live)
  coordinator/   → FastAPI DJ coordinator API (api.mcav.live)
```

Both services deploy to [Railway](https://railway.app) as separate services from this monorepo.

## Site (`site/`)

Modern Next.js landing page with:
- Product overview and feature showcase
- "Join Show" connect code entry
- Demo video embed
- Links to docs, GitHub, Discord

**Stack:** Next.js 15, TypeScript, Tailwind CSS, App Router

```bash
cd site
npm install
npm run dev     # http://localhost:3000
npm run build   # Production build
```

## Coordinator (`coordinator/`)

Central DJ coordination service that:
- Registers VJ servers and manages shows
- Generates connect codes (WORD-XXXX format, e.g. BASS-K7M2)
- Resolves codes to VJ server WebSocket URLs + JWT tokens
- Rate-limits public endpoints

**Stack:** FastAPI, SQLAlchemy 2.0 (async), PostgreSQL, PyJWT

```bash
cd coordinator
pip install -e ".[dev]"
uvicorn app.main:app --reload --port 8090   # http://localhost:8090
pytest                                       # Run tests
```

### API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health` | None | Health check |
| `POST` | `/api/v1/servers/register` | None | Register a VJ server |
| `PUT` | `/api/v1/servers/{id}/heartbeat` | API Key | Keep-alive |
| `POST` | `/api/v1/shows` | API Key | Create a show → get connect code |
| `DELETE` | `/api/v1/shows/{id}` | API Key | End a show |
| `GET` | `/api/v1/connect/{code}` | None | Resolve code → WebSocket URL + JWT |

## Deployment (Railway)

Both services deploy automatically from `main` branch:

- **Site**: Builds and serves the Next.js app
- **Coordinator**: Runs the FastAPI app with PostgreSQL

### Environment Variables

**Coordinator:**
```env
MCAV_DATABASE_URL=postgresql+asyncpg://...   # Railway provides this
MCAV_JWT_DEFAULT_EXPIRY_MINUTES=15
MCAV_CORS_ORIGINS=["https://mcav.live","http://localhost:3000"]
```

### Custom Domains
- `mcav.live` → site service
- `api.mcav.live` → coordinator service

## Related

- [minecraft-audio-viz](https://github.com/ryanthemcpherson/minecraft-audio-viz) — Main MCAV project (audio processor, MC plugin, DJ client)
- [Coordinator Architecture](https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/docs/COORDINATOR_ARCHITECTURE.md) — Full design doc

## License

MIT
