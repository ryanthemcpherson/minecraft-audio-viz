# MCAV Deployment & Domain Strategy

> Practical guide for taking the Minecraft Audio Visualizer from localhost to production.
> Covers domain selection, hosting architecture, CI/CD enhancements, Docker strategy,
> environment management, monitoring, and rollback procedures.

## Status Update (2026-02-07)

- **mcav.live** domain purchased and transferred to Cloudflare DNS (propagating)
- **Hosting decision: Railway** ($5/mo hobby tier) replaces Cloudflare Pages + Fly.io
  - Better DX, native monorepo support, PostgreSQL included, WebSocket support
  - Deployed from [ryanthemcpherson/mcav-site](https://github.com/ryanthemcpherson/mcav-site) (private repo)
- **mcav-site repo** created with:
  - `site/` — Next.js 15 landing page (Tailwind, dark theme, App Router)
  - `coordinator/` — FastAPI DJ coordinator (connect codes, JWT auth, rate limiting)
- **Main repo** may go private; mcav-site is already private

### Railway Architecture
```
Railway Project: mcav-site
  |
  +-- Service: site          → Next.js app  → mcav.live
  +-- Service: coordinator   → FastAPI app  → api.mcav.live
  +-- Service: postgres      → PostgreSQL   → internal
```

---

## Table of Contents

1. [Domain Strategy](#1-domain-strategy)
2. [Hosting Architecture](#2-hosting-architecture)
3. [Cost Analysis](#3-cost-analysis)
4. [CI/CD Pipeline](#4-cicd-pipeline)
5. [Environment Management](#5-environment-management)
6. [Docker Strategy](#6-docker-strategy)
7. [Monitoring & Observability](#7-monitoring--observability)
8. [Security](#8-security)
9. [Rollback Strategy](#9-rollback-strategy)
10. [Implementation Checklist](#10-implementation-checklist)

---

## 1. Domain Strategy

### Top Domain Picks

Ranked by value, all verified available on Namecheap as of writing.

| Rank | Domain             | Price/yr | Notes                                     |
|------|--------------------|----------|--------------------------------------------|
| 1    | **mcav.live**      | $2.98    | 91% off first year. `.live` TLD is perfect for live audio visualization |
| 2    | **mcav.dev**       | $12.98   | Developer-focused, auto-HTTPS via HSTS preload |
| 3    | **mcav.io**        | $34.98   | Industry-standard tech TLD                 |
| 4    | **blockbeats.live**| $2.98    | Descriptive, memorable for non-technical users |
| 5    | **mcav.gg**        | $68.98   | Gaming community standard (`.gg` = good game) |

**Taken / unavailable:**
- `mcav.tech` -- registered by an AV company since 2023
- `mcav.cc` -- taken
- `blockbeats.io` -- taken
- `blockbeats.xyz` -- taken

**Decision:** **mcav.live** -- PURCHASED. Transferred to Cloudflare DNS. The `.live` TLD
perfectly communicates what the product does (live audio visualization).

### Subdomain Architecture

```
mcav.live                    Landing page (project overview, demo video, download links)
  |
  +-- app.mcav.live          Admin panel / DJ control interface
  +-- preview.mcav.live      3D browser preview (Three.js)
  +-- dj.mcav.live           VJ coordinator API (WebSocket + REST)
  +-- api.mcav.live          General REST API (future: presets marketplace, analytics)
  +-- docs.mcav.live         Documentation site (MkDocs or Docusaurus)
```

**DNS configuration** (Cloudflare):

| Record | Name       | Type  | Target                              | Proxy |
|--------|------------|-------|--------------------------------------|-------|
| Root   | `@`        | CNAME | `mcav.pages.dev`                    | Yes   |
| App    | `app`      | CNAME | `mcav.pages.dev`                    | Yes   |
| Preview| `preview`  | CNAME | `mcav.pages.dev`                    | Yes   |
| DJ API | `dj`       | CNAME | `mcav-vj.fly.dev`                   | Yes   |
| API    | `api`      | CNAME | `mcav-vj.fly.dev`                   | Yes   |
| Docs   | `docs`     | CNAME | `mcav.pages.dev` or GH Pages CNAME  | Yes   |

All subdomains proxy through Cloudflare for DDoS protection and caching. WebSocket
connections (`dj.mcav.live`) require Cloudflare WebSocket support, which is included on the
free tier.

---

## 2. Hosting Architecture

### Overview Diagram

```
                     +-------------------+
                     |   Cloudflare DNS  |
                     |   + CDN + WAF     |
                     +--------+----------+
                              |
         +--------------------+---------------------+
         |                    |                      |
+--------v--------+  +-------v--------+  +----------v----------+
| Cloudflare Pages|  |    Fly.io      |  | Cloudflare Tunnel   |
| (Free)          |  |    (Free tier) |  | (Free)              |
|                 |  |                |  |                     |
| - Landing page  |  | - VJ server   |  | - MC server (8765)  |
| - Admin panel   |  | - DJ coord.   |  | - RCON (25575)      |
| - 3D preview    |  | - REST API    |  |                     |
| - Documentation |  |                |  |                     |
+-----------------+  +----------------+  +---------------------+
                              |                      |
                              v                      v
                     +--------+--------+   +---------+---------+
                     | MC Plugin (8765)|   | Dev Server        |
                     | via CF Tunnel   |   | 192.168.1.204     |
                     +-----------------+   +-------------------+
```

### Static Assets -- Cloudflare Pages (Free)

Host all browser-facing static content on Cloudflare Pages.

**What gets deployed:**
- `admin_panel/` -- DJ control interface
- `preview_tool/frontend/` -- Three.js 3D preview
- Landing page (root domain)

**Why Cloudflare Pages:**
- Unlimited bandwidth on free tier
- Global CDN with 300+ edge locations
- Automatic HTTPS with managed certificates
- GitHub integration with preview deployments on PRs
- Instant rollbacks via the dashboard

**Setup:**
```bash
# Install Wrangler CLI
npm install -g wrangler

# Authenticate
wrangler login

# Create the Pages project
wrangler pages project create mcav

# Deploy admin panel
wrangler pages deploy admin_panel --project-name=mcav

# For multi-site routing, use a _routes.json or a build step
# that assembles all static sites into a single output directory.
```

**Multi-subdomain strategy:** Use a single Cloudflare Pages project with path-based routing,
or create separate projects per subdomain:

| Subdomain           | Pages Project     | Build Output        |
|---------------------|-------------------|----------------------|
| `mcav.live`         | `mcav-landing`    | `landing/`          |
| `app.mcav.live`     | `mcav-admin`      | `admin_panel/`      |
| `preview.mcav.live` | `mcav-preview`    | `preview_tool/frontend/` |
| `docs.mcav.live`    | `mcav-docs`       | `docs-site/build/`  |

### VJ Server / Coordinator API -- Fly.io (Free Tier)

The VJ server (`audio_processor/vj_server.py`) runs as a long-lived Python process that
coordinates multi-DJ sessions and broadcasts audio state to Minecraft + browser clients.

**Fly.io free tier includes:**
- 3 shared-cpu-1x VMs (256 MB RAM each)
- 3 GB persistent volume storage
- 160 GB outbound bandwidth/month
- Native WebSocket support
- Auto-sleep for idle apps (saves resources)
- Docker-based deployment

**Why Fly.io over alternatives:**
- Native WebSocket support (unlike many PaaS providers)
- Free tier is generous enough for a coordinator service
- Global edge deployment possible (Fly runs VMs close to users)
- Simple `flyctl deploy` workflow
- Built-in metrics, logs, and health checks

**Fly.io setup:**
```bash
# Install flyctl
curl -L https://fly.io/install.sh | sh

# Authenticate
fly auth signup  # or fly auth login

# Initialize app from project root (uses existing Dockerfile)
fly launch --name mcav-vj --region iad --no-deploy

# Set secrets
fly secrets set MINECRAFT_HOST=your-tunnel-host.cfargotunnel.com
fly secrets set DJ_AUTH_SECRET=your-jwt-secret-here

# Deploy
fly deploy

# Check status
fly status
fly logs
```

**fly.toml:**
```toml
app = "mcav-vj"
primary_region = "iad"

[build]
  dockerfile = "Dockerfile"

[env]
  VJ_SERVER_PORT = "9000"
  BROADCAST_PORT = "8766"
  HTTP_PORT = "8080"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "suspend"
  auto_start_machines = true
  min_machines_running = 0

[[services]]
  internal_port = 9000
  protocol = "tcp"
  [[services.ports]]
    port = 9000
    handlers = ["tls"]

[[services]]
  internal_port = 8766
  protocol = "tcp"
  [[services.ports]]
    port = 8766
    handlers = ["tls"]

[checks]
  [checks.health]
    type = "tcp"
    port = 9000
    interval = "30s"
    timeout = "5s"
```

### Minecraft Server Tunneling -- Cloudflare Tunnel (Free)

Expose the Minecraft server's WebSocket port (8765) to the internet without opening ports
on the home router. This lets the Fly.io VJ server reach the MC plugin.

**Why Cloudflare Tunnel:**
- No port forwarding required on home network
- Zero-trust access model
- Free for unlimited tunnels
- DDoS protection included
- Works behind CGNAT

**Setup on the Minecraft server (192.168.1.204):**
```bash
# Install cloudflared
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
  -o /usr/local/bin/cloudflared
chmod +x /usr/local/bin/cloudflared

# Authenticate
cloudflared tunnel login

# Create tunnel
cloudflared tunnel create mcav-mc

# Configure tunnel
cat > ~/.cloudflared/config.yml << 'EOF'
tunnel: mcav-mc
credentials-file: /home/ryan/.cloudflared/<TUNNEL_ID>.json

ingress:
  # WebSocket for audio viz plugin
  - hostname: mc-ws.mcav.live
    service: ws://localhost:8765
    originRequest:
      noTLSVerify: true

  # RCON access (restrict via Cloudflare Access)
  - hostname: mc-rcon.mcav.live
    service: tcp://localhost:25575

  # Catch-all
  - service: http_status:404
EOF

# Route DNS
cloudflared tunnel route dns mcav-mc mc-ws.mcav.live
cloudflared tunnel route dns mcav-mc mc-rcon.mcav.live

# Run as systemd service
sudo cloudflared service install
sudo systemctl enable cloudflared
sudo systemctl start cloudflared
```

### Documentation -- Cloudflare Pages or GitHub Pages

Documentation can live on either platform. GitHub Pages is simpler if you already use it;
Cloudflare Pages gives you the same CDN as the rest of the project.

**Option A: Cloudflare Pages (recommended for consistency)**
```bash
# Build docs with MkDocs
pip install mkdocs mkdocs-material
mkdocs build --site-dir docs-site/build

# Deploy
wrangler pages deploy docs-site/build --project-name=mcav-docs
```

**Option B: GitHub Pages**
```bash
# In repo settings, enable GitHub Pages from docs/ folder or gh-pages branch
# Add CNAME file for custom domain
echo "docs.mcav.live" > docs-site/build/CNAME
```

---

## 3. Cost Analysis

### Monthly Cost Breakdown

| Component              | Provider         | Tier       | Monthly Cost | Annual Cost |
|------------------------|------------------|------------|--------------|-------------|
| Domain (`mcav.live`)   | Namecheap        | Standard   | ~$0.25       | $2.98       |
| Static hosting         | Cloudflare Pages | Free       | $0.00        | $0.00       |
| CDN + DDoS protection  | Cloudflare       | Free       | $0.00        | $0.00       |
| VJ server              | Fly.io           | Free tier  | $0.00        | $0.00       |
| MC tunnel              | Cloudflare Tunnel| Free       | $0.00        | $0.00       |
| Container registry     | GitHub (GHCR)    | Free       | $0.00        | $0.00       |
| CI/CD                  | GitHub Actions   | Free (2k min/mo) | $0.00   | $0.00       |
| Monitoring (uptime)    | UptimeRobot      | Free (50 monitors) | $0.00 | $0.00       |
| DNS management         | Cloudflare       | Free       | $0.00        | $0.00       |
| **Total**              |                  |            | **~$0.25**   | **~$2.98**  |

### Cost If You Scale

| Scenario                     | Additional Cost | Provider          |
|------------------------------|-----------------|-------------------|
| High traffic (>100k req/day) | $0 (CF Pages unlimited) | Cloudflare |
| VJ server needs more RAM     | $1.94/mo (512MB VM) | Fly.io        |
| Multiple VJ server regions   | $1.94/mo per region  | Fly.io       |
| Custom email (hello@mcav.live)| $0 (CF Email Routing) | Cloudflare  |
| SSL wildcard cert             | $0 (included)        | Cloudflare  |

**Bottom line:** The entire production stack runs for under $3/year. The only hard cost is
the domain name.

---

## 4. CI/CD Pipeline

### Existing Workflows (Already in `.github/workflows/`)

The project already has a solid CI/CD foundation:

| Workflow          | File               | Trigger                        | Purpose                                |
|-------------------|--------------------|--------------------------------|----------------------------------------|
| **CI**            | `ci.yml`           | Push to `main`/`develop`, PRs  | Python lint + test, Java build, gate job |
| **Python CI**     | `python-ci.yml`    | Push/PR (Python paths)         | Ruff lint, pytest (3.10/3.11/3.12), build |
| **Java CI**       | `java-ci.yml`      | Push/PR (Java paths)           | Maven build, artifact upload           |
| **DJ Client CI**  | `dj-client-ci.yml` | Push/PR (DJ client paths)      | TSC, Clippy, Tauri build (Linux/Win/Mac) |
| **Docker**        | `docker.yml`       | Push to `main`, tags           | Build + push to GHCR                   |
| **Deploy**        | `deploy.yml`       | Push to `main`, manual         | SSH deploy to dev server (192.168.1.204) |
| **Release**       | `release.yml`      | Tag `v*`                       | Build all artifacts, create GitHub Release |

### Proposed New Workflows

#### 4.1 `deploy-web.yml` -- Cloudflare Pages Deployment

Deploys static frontends to Cloudflare Pages on every push to `main`.

```yaml
name: Deploy Web (Cloudflare Pages)

on:
  push:
    branches: [main]
    paths:
      - 'admin_panel/**'
      - 'preview_tool/frontend/**'
      - '.github/workflows/deploy-web.yml'
  workflow_dispatch:

concurrency:
  group: deploy-web
  cancel-in-progress: true

jobs:
  deploy-admin:
    name: Deploy Admin Panel
    runs-on: ubuntu-latest
    if: >-
      github.event_name == 'workflow_dispatch' ||
      contains(join(github.event.commits.*.modified, ','), 'admin_panel/')
    steps:
      - uses: actions/checkout@v4

      - name: Deploy to Cloudflare Pages
        uses: cloudflare/wrangler-action@v3
        with:
          apiToken: ${{ secrets.CLOUDFLARE_API_TOKEN }}
          accountId: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}
          command: pages deploy admin_panel --project-name=mcav-admin --commit-dirty=true

      - name: Comment deploy URL
        if: github.event_name == 'pull_request'
        uses: actions/github-script@v7
        with:
          script: |
            github.rest.issues.createComment({
              issue_number: context.issue.number,
              owner: context.repo.owner,
              repo: context.repo.repo,
              body: 'Admin panel deployed to preview: https://mcav-admin.pages.dev'
            })

  deploy-preview:
    name: Deploy 3D Preview
    runs-on: ubuntu-latest
    if: >-
      github.event_name == 'workflow_dispatch' ||
      contains(join(github.event.commits.*.modified, ','), 'preview_tool/')
    steps:
      - uses: actions/checkout@v4

      - name: Deploy to Cloudflare Pages
        uses: cloudflare/wrangler-action@v3
        with:
          apiToken: ${{ secrets.CLOUDFLARE_API_TOKEN }}
          accountId: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}
          command: pages deploy preview_tool/frontend --project-name=mcav-preview --commit-dirty=true
```

#### 4.2 `deploy-api.yml` -- Fly.io VJ Server Deployment

Deploys the VJ server container to Fly.io on pushes to `main`.

```yaml
name: Deploy API (Fly.io)

on:
  push:
    branches: [main]
    paths:
      - 'audio_processor/**'
      - 'python_client/**'
      - 'Dockerfile'
      - 'fly.toml'
      - '.github/workflows/deploy-api.yml'
  workflow_dispatch:

concurrency:
  group: deploy-api
  cancel-in-progress: false  # Don't interrupt in-progress deploys

jobs:
  test:
    name: Pre-deploy Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: '3.12'

      - name: Install dependencies
        run: |
          python -m pip install --upgrade pip
          pip install -e ".[dev]"

      - name: Run tests
        run: pytest audio_processor/tests/ -v --tb=short

  deploy:
    name: Deploy to Fly.io
    needs: test
    runs-on: ubuntu-latest
    environment: production

    steps:
      - uses: actions/checkout@v4

      - name: Setup Fly.io CLI
        uses: superfly/flyctl-actions/setup-flyctl@master

      - name: Deploy to Fly.io
        run: flyctl deploy --remote-only
        env:
          FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}

      - name: Verify deployment
        run: |
          sleep 10
          flyctl status
          flyctl checks list
        env:
          FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}

      - name: Deploy summary
        if: always()
        run: |
          echo "### Fly.io Deploy Summary" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "- **App:** mcav-vj" >> $GITHUB_STEP_SUMMARY
          echo "- **Region:** iad" >> $GITHUB_STEP_SUMMARY
          echo "- **Commit:** \`${{ github.sha }}\`" >> $GITHUB_STEP_SUMMARY
          echo "- **URL:** https://dj.mcav.live" >> $GITHUB_STEP_SUMMARY
```

#### 4.3 Release Workflow Enhancements

The existing `release.yml` already builds Python packages, the MC plugin JAR, and DJ client
binaries for all platforms. Proposed additions for publishing to package registries:

```yaml
# Add these jobs to the existing release.yml, after the 'release' job

  publish-pypi:
    name: Publish to PyPI
    needs: release
    runs-on: ubuntu-latest
    environment: pypi
    permissions:
      id-token: write  # Required for trusted publishing
    steps:
      - name: Download Python artifacts
        uses: actions/download-artifact@v4
        with:
          name: python-dist
          path: dist/

      - name: Publish to PyPI
        uses: pypa/gh-action-pypi-publish@release/v1
        # Uses trusted publishing (OIDC) -- no API token needed
        # Configure at https://pypi.org/manage/project/audioviz/settings/publishing/

  publish-modrinth:
    name: Publish to Modrinth
    needs: release
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Download Plugin JAR
        uses: actions/download-artifact@v4
        with:
          name: minecraft-plugin
          path: plugin/

      - name: Get version from tag
        id: version
        run: echo "VERSION=${GITHUB_REF#refs/tags/v}" >> $GITHUB_OUTPUT

      - name: Publish to Modrinth
        uses: Kir-Antipov/mc-publish@v3.3
        with:
          modrinth-id: <your-modrinth-project-id>
          modrinth-token: ${{ secrets.MODRINTH_TOKEN }}
          files: plugin/audioviz-plugin-*.jar
          name: AudioViz ${{ steps.version.outputs.VERSION }}
          version: ${{ steps.version.outputs.VERSION }}
          version-type: release
          game-versions: "1.21.1"
          loaders: paper
          dependencies: ""

  # Future: publish Tauri DJ client to crates.io
  # publish-crates:
  #   name: Publish to crates.io
  #   needs: release
  #   runs-on: ubuntu-latest
  #   steps:
  #     - uses: actions/checkout@v4
  #     - uses: dtolnay/rust-toolchain@stable
  #     - name: Publish
  #       working-directory: dj_client/src-tauri
  #       run: cargo publish
  #       env:
  #         CARGO_REGISTRY_TOKEN: ${{ secrets.CRATES_IO_TOKEN }}
```

### Required GitHub Actions Secrets

| Secret                    | Purpose                          | Where to Get It                          |
|---------------------------|----------------------------------|-------------------------------------------|
| `CLOUDFLARE_API_TOKEN`    | Cloudflare Pages deploy          | CF Dashboard > My Profile > API Tokens   |
| `CLOUDFLARE_ACCOUNT_ID`   | Cloudflare account identifier    | CF Dashboard > Account Home (right sidebar) |
| `FLY_API_TOKEN`           | Fly.io deployment                | `fly tokens create deploy`               |
| `DEV_SSH_KEY`             | Dev server SSH (already exists)  | SSH key for 192.168.1.204                |
| `MODRINTH_TOKEN`          | Modrinth plugin publishing       | Modrinth Settings > Authorization tokens |
| `CRATES_IO_TOKEN`         | crates.io publishing (future)    | crates.io Account Settings               |

---

## 5. Environment Management

### Three-Tier Environment Strategy

```
+-------------------+     +-------------------+     +-------------------+
|   Development     |     |     Staging        |     |    Production     |
|   (localhost)     | --> |   (dev server)     | --> |   (cloud)         |
+-------------------+     +-------------------+     +-------------------+
| Python: local venv|     | Python: venv on    |     | Python: Docker on |
| MC: localhost     |     |   192.168.1.204    |     |   Fly.io          |
| Admin: file://    |     | MC: 192.168.1.204  |     | MC: CF Tunnel     |
| Preview: file://  |     | Admin: :8081       |     | Admin: CF Pages   |
| Hot-reload: yes   |     | Preview: :8080     |     | Preview: CF Pages |
| .env: local       |     | .env: server       |     | .env: fly secrets |
+-------------------+     +-------------------+     +-------------------+
```

### Development (Local Machine)

```bash
# Setup
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
cp .env.example .env

# Python virtual environment
python -m venv .venv
.venv\Scripts\activate          # Windows
pip install -e ".[dev,full]"

# Run audio processor locally
audioviz --app spotify --test   # No Minecraft needed

# Hot-reload Minecraft plugin during dev
# 1. Build: cd minecraft_plugin && mvn package
# 2. Copy JAR to plugins/
# 3. In-game: /plugman reload AudioViz

# Admin panel local dev (just open the file)
start admin_panel/index.html    # Windows
# Or use a simple HTTP server:
python -m http.server 8081 --directory admin_panel --bind 127.0.0.1
```

**.env (development):**
```env
MINECRAFT_HOST=localhost
MINECRAFT_PORT=8765
VJ_SERVER_PORT=9000
BROADCAST_PORT=8766
LOG_LEVEL=DEBUG
```

### Staging (Dev Server -- 192.168.1.204)

The existing `deploy.yml` workflow handles staging deployments via SSH + rsync. The
`deploy.sh` script manages process lifecycle on the server.

```bash
# Manual deploy (from dev server)
cd /home/ryan/minecraft-audio-viz
./deploy.sh --force

# Or trigger via GitHub Actions
# Push to main -> deploy.yml runs automatically
# Or: Actions tab -> "Deploy to Dev Server" -> Run workflow
```

**.env (staging):**
```env
MINECRAFT_HOST=localhost
MINECRAFT_PORT=8765
VJ_SERVER_PORT=9000
BROADCAST_PORT=8766
LOG_LEVEL=INFO
```

**Staging services (managed by deploy.sh):**
- `minecraft.service` -- systemd unit for the MC server
- tmux session `admin` -- HTTP server on port 8081
- tmux session `preview` -- HTTP server on port 8080
- Background process -- VJ server on port 9000

### Production (Cloud)

```bash
# Deploy static sites
wrangler pages deploy admin_panel --project-name=mcav-admin
wrangler pages deploy preview_tool/frontend --project-name=mcav-preview

# Deploy VJ server
fly deploy

# Secrets are managed via Fly.io (never in the repo)
fly secrets set MINECRAFT_HOST=mc-ws.mcav.live
fly secrets set DJ_AUTH_SECRET=$(openssl rand -hex 32)
```

**Production secrets (Fly.io):**
```bash
fly secrets list
# NAME              DIGEST                  CREATED AT
# MINECRAFT_HOST    abc123...               2024-01-15
# DJ_AUTH_SECRET    def456...               2024-01-15
```

---

## 6. Docker Strategy

### Existing Docker Setup

The project already has a working `Dockerfile` and `docker-compose.yml` for the VJ server.
The current Dockerfile uses `python:3.11-slim`, installs dependencies directly via pip, and
exposes ports 9000 (DJ), 8766 (broadcast), and 8080 (HTTP admin).

### Optimized Production Dockerfile

Proposed improvements over the existing Dockerfile:

```dockerfile
# Dockerfile.production
# Optimized for Fly.io deployment with smaller image and UV for fast installs

FROM python:3.12-slim AS base

# Shared environment
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# ---------------------------------------------------------------------------
# Stage 1: Build dependencies
# ---------------------------------------------------------------------------
FROM base AS builder

# Install UV for faster dependency resolution
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy only dependency metadata first (Docker layer caching)
COPY pyproject.toml ./

# Install dependencies into a virtual environment
RUN uv venv /app/.venv && \
    uv pip install --python /app/.venv/bin/python \
        numpy>=1.24.0 \
        scipy>=1.11.0 \
        websockets>=12.0 \
        python-dotenv>=1.0.0 \
        bcrypt>=4.0.0

# ---------------------------------------------------------------------------
# Stage 2: Production image
# ---------------------------------------------------------------------------
FROM base AS production

WORKDIR /app

# Copy virtual environment from builder
COPY --from=builder /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH"

# Copy application code
COPY audio_processor/ ./audio_processor/
COPY python_client/ ./python_client/
COPY admin_panel/ ./admin_panel/
COPY preview_tool/ ./preview_tool/
COPY configs/ ./configs/

# Create non-root user
RUN useradd -m -u 1000 audioviz && \
    chown -R audioviz:audioviz /app
USER audioviz

# Expose ports
EXPOSE 9000 8766 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD python -c "import socket; s=socket.socket(); s.connect(('localhost', 9000)); s.close()"

# Default command
ENTRYPOINT ["python", "-m", "audio_processor.vj_server"]
CMD ["--dj-port", "9000", "--broadcast-port", "8766", "--http-port", "8080"]
```

Key improvements:
- **Multi-stage build** -- build dependencies (gcc) are not in the final image
- **UV package manager** -- significantly faster dependency installation
- **Python 3.12** -- performance improvements over 3.11
- **Layer caching** -- `pyproject.toml` is copied before application code so dependency
  layers are cached when only code changes

### Docker Compose for Local Development

The existing `docker-compose.yml` is already well-structured. Here is an extended version
that includes a development profile:

```yaml
# docker-compose.dev.yml
# Usage: docker compose -f docker-compose.yml -f docker-compose.dev.yml up

services:
  vj-server:
    build:
      context: .
      target: production  # Use the multi-stage Dockerfile
    volumes:
      # Mount source code for live reload during development
      - ./audio_processor:/app/audio_processor:ro
      - ./python_client:/app/python_client:ro
      - ./admin_panel:/app/admin_panel:ro
      - ./preview_tool:/app/preview_tool:ro
      - ./configs:/app/configs:ro
    environment:
      - LOG_LEVEL=DEBUG
      - MINECRAFT_HOST=host.docker.internal  # Access host MC server
      - MINECRAFT_PORT=8765
```

### Building and Running

```bash
# Development (with live code mounting)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build

# Production (uses baked-in code)
docker compose up -d --build

# Build for Fly.io (uses GHCR)
docker build -t ghcr.io/ryanthemcpherson/minecraft-audio-viz:latest .
docker push ghcr.io/ryanthemcpherson/minecraft-audio-viz:latest

# Or just let Fly.io build it remotely
fly deploy --remote-only
```

---

## 7. Monitoring & Observability

### Health Endpoints

The VJ server should expose a health endpoint for monitoring:

```
GET /health
Response: {"status": "ok", "uptime": 3600, "djs_connected": 2, "clients_connected": 5}

GET /metrics
Response: {"frames_sent": 123456, "avg_latency_ms": 12, "memory_mb": 45}
```

### Fly.io Built-in Monitoring

```bash
# Live logs
fly logs --app mcav-vj

# Machine status
fly status --app mcav-vj

# Health check results
fly checks list --app mcav-vj

# Metrics dashboard
fly dashboard --app mcav-vj
# Opens: https://fly.io/apps/mcav-vj/monitoring
```

Fly.io provides built-in metrics for:
- CPU and memory usage
- Network I/O
- Request count and latency
- Health check pass/fail history

### Cloudflare Analytics

Cloudflare Pages provides free analytics:
- Page views and unique visitors per subdomain
- Bandwidth usage
- Top referrers and paths
- Cache hit ratio
- Error rates (4xx, 5xx)

Access via: Cloudflare Dashboard > Pages > mcav-admin > Analytics

### UptimeRobot (Free Tier)

Set up free uptime monitoring for all public endpoints:

| Monitor Name        | URL                           | Check Interval | Alert |
|---------------------|-------------------------------|----------------|-------|
| MCAV Landing        | `https://mcav.live`           | 5 min          | Yes   |
| Admin Panel         | `https://app.mcav.live`       | 5 min          | Yes   |
| 3D Preview          | `https://preview.mcav.live`   | 5 min          | Yes   |
| VJ Server Health    | `https://dj.mcav.live/health` | 5 min          | Yes   |
| Docs                | `https://docs.mcav.live`      | 15 min         | Yes   |

UptimeRobot free tier: 50 monitors, 5-minute checks, email + webhook alerts.

### Discord Webhook Notifications

Send deployment and downtime alerts to a Discord channel:

```bash
# GitHub Actions: Add to deploy jobs
- name: Notify Discord
  if: always()
  run: |
    STATUS="${{ job.status }}"
    COLOR=$([[ "$STATUS" == "success" ]] && echo "3066993" || echo "15158332")
    curl -H "Content-Type: application/json" \
      -d "{
        \"embeds\": [{
          \"title\": \"Deploy $STATUS\",
          \"description\": \"Commit: \`${{ github.sha }}\`\nBranch: \`${{ github.ref_name }}\`\",
          \"color\": $COLOR,
          \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
        }]
      }" \
      ${{ secrets.DISCORD_WEBHOOK_URL }}
```

UptimeRobot also supports Discord webhooks natively -- configure in the UptimeRobot
alert contacts settings.

---

## 8. Security

### HTTPS Everywhere

- **Cloudflare Pages:** Automatic HTTPS, managed certificates, HSTS headers
- **Fly.io:** Automatic TLS termination, managed certificates
- **Cloudflare Tunnel:** End-to-end encrypted tunnel, no exposed ports
- **WebSocket connections:** `wss://` enforced via Cloudflare proxy

Cloudflare SSL/TLS settings:
```
SSL Mode: Full (Strict)
Minimum TLS Version: 1.2
Always Use HTTPS: On
HSTS: Enabled (max-age=31536000, includeSubDomains)
```

### DDoS Protection

Cloudflare free tier includes:
- Layer 3/4 DDoS protection (automatic)
- Layer 7 rate limiting (basic, via WAF rules)
- Bot management (basic)
- IP reputation filtering

### Rate Limiting

Configure Cloudflare WAF rules for the API:

| Rule                     | Path              | Rate          | Action   |
|--------------------------|-------------------|---------------|----------|
| API general              | `dj.mcav.live/*`  | 100 req/min   | Challenge|
| WebSocket connect        | `dj.mcav.live/ws` | 10 req/min    | Block    |
| Health check (exempt)    | `*/health`        | No limit      | Allow    |

```
# Cloudflare WAF Rule (expression)
(http.host eq "dj.mcav.live" and http.request.uri.path ne "/health")
Rate: 100 requests per minute per IP
Action: Managed Challenge
```

### Secrets Management

**Never commit secrets.** Use environment-specific secret stores:

| Environment | Secret Store            | Access Method                |
|-------------|-------------------------|-------------------------------|
| Development | `.env` file (gitignored)| `python-dotenv` auto-load    |
| Staging     | `.env` on dev server    | SSH access only              |
| Production  | Fly.io secrets          | `fly secrets set KEY=value`  |
| CI/CD       | GitHub Actions secrets  | `${{ secrets.KEY }}`         |

**Required secrets checklist:**
```
GitHub Actions:
  CLOUDFLARE_API_TOKEN      # CF Pages deployment
  CLOUDFLARE_ACCOUNT_ID     # CF account ID
  FLY_API_TOKEN             # Fly.io deployment
  DEV_SSH_KEY               # SSH to 192.168.1.204
  DISCORD_WEBHOOK_URL       # Deploy notifications
  MODRINTH_TOKEN            # Plugin publishing

Fly.io:
  MINECRAFT_HOST            # CF Tunnel hostname for MC server
  DJ_AUTH_SECRET            # JWT signing secret for DJ auth

Dev Server (.env):
  MINECRAFT_HOST=localhost
  MINECRAFT_PORT=8765
```

### GitHub Actions Security

```yaml
# In workflow files, use minimal permissions
permissions:
  contents: read    # Default: read-only
  packages: write   # Only for Docker push workflows

# Pin action versions to commit SHAs for supply-chain safety
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11  # v4.1.1
```

---

## 9. Rollback Strategy

### Cloudflare Pages -- Instant Rollback

Cloudflare Pages keeps every deployment as an immutable snapshot. Rollback takes seconds.

```bash
# List recent deployments
wrangler pages deployment list --project-name=mcav-admin

# Rollback to a specific deployment
wrangler pages deployment rollback --project-name=mcav-admin <deployment-id>

# Or use the Cloudflare Dashboard:
# Pages > mcav-admin > Deployments > click "Rollback to this deploy"
```

### Fly.io -- Release Rollback

```bash
# List recent releases
fly releases --app mcav-vj

# Rollback to the previous release
fly deploy --image <previous-image-ref> --app mcav-vj

# Or scale down and redeploy
fly machine stop --app mcav-vj
fly deploy --app mcav-vj

# View release details
fly releases --app mcav-vj --json | jq '.[0]'
```

### Minecraft Plugin -- PlugManX Hot Reload

For the Minecraft plugin, rollback by replacing the JAR and reloading:

```bash
# On the Minecraft server (192.168.1.204)
cd /home/ryan/minecraft-server/plugins

# Keep previous version as backup (deploy.sh should do this automatically)
cp audioviz-plugin-1.0.0-SNAPSHOT.jar audioviz-plugin-1.0.0-SNAPSHOT.jar.bak

# Restore previous JAR
cp audioviz-plugin-1.0.0-SNAPSHOT.jar.bak audioviz-plugin-1.0.0-SNAPSHOT.jar

# In-game reload (no server restart needed)
/plugman reload AudioViz
```

For a full server-level rollback:
```bash
# Rollback via Git on the dev server
ssh ryan@192.168.1.204
cd /home/ryan/minecraft-audio-viz
git log --oneline -10           # Find the good commit
git reset --hard <good-commit>
./deploy.sh --force
```

### Docker Image Rollback

```bash
# GHCR stores all tagged images
docker pull ghcr.io/ryanthemcpherson/minecraft-audio-viz:main  # Latest main
docker pull ghcr.io/ryanthemcpherson/minecraft-audio-viz:<sha>  # Specific commit

# Rollback local Docker Compose
docker compose down
docker compose up -d --pull always  # Or specify an older image tag in .env
```

### Rollback Decision Matrix

| Symptom                            | Component    | Rollback Method               | Time     |
|------------------------------------|--------------|-------------------------------|----------|
| Admin panel broken                 | CF Pages     | Dashboard rollback            | ~10 sec  |
| Preview tool rendering issues      | CF Pages     | Dashboard rollback            | ~10 sec  |
| VJ server crash/hang               | Fly.io       | `fly deploy --image <prev>`   | ~60 sec  |
| MC plugin entity glitches          | Plugin JAR   | PlugManX reload with backup   | ~5 sec   |
| Audio processing regression        | Python pkg   | Git revert + redeploy         | ~3 min   |
| Full system failure                | All          | Git revert + full redeploy    | ~10 min  |

---

## 10. Implementation Checklist

Step-by-step guide to go from the current localhost setup to full production.

### Phase 1: Domain & DNS (Day 1)

- [ ] Register `mcav.live` on Namecheap ($2.98)
- [ ] Create Cloudflare account (free)
- [ ] Transfer DNS to Cloudflare (change nameservers at Namecheap)
- [ ] Wait for DNS propagation (up to 24 hours, usually faster)
- [ ] Enable Cloudflare SSL/TLS "Full (Strict)" mode
- [ ] Enable "Always Use HTTPS"
- [ ] Enable HSTS

### Phase 2: Static Hosting (Day 1-2)

- [ ] Create Cloudflare Pages project `mcav-admin`
- [ ] Create Cloudflare Pages project `mcav-preview`
- [ ] Deploy admin panel: `wrangler pages deploy admin_panel --project-name=mcav-admin`
- [ ] Deploy preview tool: `wrangler pages deploy preview_tool/frontend --project-name=mcav-preview`
- [ ] Configure custom domain `app.mcav.live` -> `mcav-admin`
- [ ] Configure custom domain `preview.mcav.live` -> `mcav-preview`
- [ ] Verify HTTPS works on both subdomains
- [ ] Add `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` to GitHub secrets

### Phase 3: VJ Server on Fly.io (Day 2-3)

- [ ] Create Fly.io account (free)
- [ ] Install flyctl: `curl -L https://fly.io/install.sh | sh`
- [ ] Create `fly.toml` in project root (see Section 2)
- [ ] Run `fly launch --name mcav-vj --region iad --no-deploy`
- [ ] Set secrets: `fly secrets set MINECRAFT_HOST=... DJ_AUTH_SECRET=...`
- [ ] Deploy: `fly deploy`
- [ ] Verify health: `fly status` and `fly logs`
- [ ] Configure custom domain `dj.mcav.live` -> `mcav-vj.fly.dev`
- [ ] Add `FLY_API_TOKEN` to GitHub secrets

### Phase 4: Minecraft Server Tunnel (Day 3-4)

- [ ] Install cloudflared on 192.168.1.204
- [ ] Run `cloudflared tunnel login`
- [ ] Create tunnel: `cloudflared tunnel create mcav-mc`
- [ ] Write `~/.cloudflared/config.yml` (see Section 2)
- [ ] Route DNS: `cloudflared tunnel route dns mcav-mc mc-ws.mcav.live`
- [ ] Install as systemd service: `sudo cloudflared service install`
- [ ] Start tunnel: `sudo systemctl start cloudflared`
- [ ] Update Fly.io secret: `fly secrets set MINECRAFT_HOST=mc-ws.mcav.live`
- [ ] Test end-to-end: DJ client -> Fly.io VJ server -> CF Tunnel -> MC plugin

### Phase 5: CI/CD Automation (Day 4-5)

- [ ] Create `.github/workflows/deploy-web.yml` (see Section 4.1)
- [ ] Create `.github/workflows/deploy-api.yml` (see Section 4.2)
- [ ] Test deploy-web: push a change to `admin_panel/` and verify CF Pages updates
- [ ] Test deploy-api: push a change to `audio_processor/` and verify Fly.io updates
- [ ] Add Discord webhook notifications to deploy workflows
- [ ] Add `DISCORD_WEBHOOK_URL` to GitHub secrets

### Phase 6: Monitoring (Day 5)

- [ ] Create UptimeRobot account (free)
- [ ] Add monitors for: `mcav.live`, `app.mcav.live`, `preview.mcav.live`, `dj.mcav.live/health`
- [ ] Configure Discord webhook alert contact in UptimeRobot
- [ ] Verify Fly.io metrics dashboard works
- [ ] Verify Cloudflare Analytics works for each Pages project
- [ ] Add `/health` endpoint to VJ server if not already present

### Phase 7: Release Pipeline Enhancements (Day 6-7)

- [ ] Set up PyPI trusted publishing for the `audioviz` package
- [ ] Add `publish-pypi` job to `release.yml`
- [ ] Create Modrinth project for the AudioViz plugin
- [ ] Add `MODRINTH_TOKEN` to GitHub secrets
- [ ] Add `publish-modrinth` job to `release.yml`
- [ ] Tag a test release `v1.0.0-rc.1` and verify all publishing works
- [ ] Create a landing page for `mcav.live` with download links

### Phase 8: Documentation Site (Day 7+)

- [ ] Choose framework (MkDocs Material recommended)
- [ ] Create `mkdocs.yml` configuration
- [ ] Build docs from existing `docs/` markdown files
- [ ] Deploy to CF Pages: `wrangler pages deploy site/ --project-name=mcav-docs`
- [ ] Configure custom domain `docs.mcav.live`
- [ ] Add docs build/deploy to `deploy-web.yml`

### Ongoing Maintenance

- [ ] Renew `mcav.live` before promo rate expires (set calendar reminder)
- [ ] Review Fly.io usage monthly to stay within free tier
- [ ] Check UptimeRobot alerts weekly
- [ ] Update dependencies quarterly (Dependabot handles PRs automatically)
- [ ] Review Cloudflare WAF analytics for abuse patterns
- [ ] Tag releases with semantic versioning: `v1.0.0`, `v1.1.0`, etc.

---

## Quick Reference Card

```
DEPLOY ADMIN PANEL:   wrangler pages deploy admin_panel --project-name=mcav-admin
DEPLOY PREVIEW:       wrangler pages deploy preview_tool/frontend --project-name=mcav-preview
DEPLOY VJ SERVER:     fly deploy
DEPLOY MC PLUGIN:     ssh ryan@192.168.1.204 && ./deploy.sh --force
VIEW VJ LOGS:         fly logs --app mcav-vj
VIEW VJ STATUS:       fly status --app mcav-vj
ROLLBACK CF PAGES:    wrangler pages deployment rollback --project-name=<name> <id>
ROLLBACK FLY.IO:      fly deploy --image <previous-image>
SET FLY SECRET:       fly secrets set KEY=value
LIST FLY SECRETS:     fly secrets list
TUNNEL STATUS:        ssh ryan@192.168.1.204 "systemctl status cloudflared"
```
