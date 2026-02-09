FROM python:3.12-slim AS builder

WORKDIR /app

# Install UV for fast dependency resolution
RUN pip install --no-cache-dir uv

COPY pyproject.toml .
RUN uv pip install --system --no-cache -e ".[dev]"

FROM python:3.12-slim

WORKDIR /app

# Copy installed packages from builder
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin

COPY . .

EXPOSE ${PORT:-8090}

CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8090}
