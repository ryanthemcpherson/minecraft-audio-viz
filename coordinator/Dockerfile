FROM python:3.12-slim

WORKDIR /app

# Install UV for fast dependency resolution and curl for healthcheck
RUN pip install --no-cache-dir uv \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy source and install — source must be present for hatchling to build the wheel
COPY . .
RUN uv pip install --system --no-cache .

EXPOSE ${PORT:-8090}

HEALTHCHECK CMD curl -f http://localhost:${PORT:-8090}/health || exit 1

CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8090}
