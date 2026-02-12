FROM python:3.12-slim

WORKDIR /app

# Ensure alembic (preDeployCommand) can import the app package from /app
ENV PYTHONPATH=/app

# Install system dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir uv

# Copy source first, then install (source must be present for hatchling build)
COPY . /app/
RUN uv pip install --system --no-cache /app/

EXPOSE ${PORT:-8090}

HEALTHCHECK CMD curl -f http://localhost:${PORT:-8090}/health || exit 1

CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8090}
