FROM python:3.12-slim

WORKDIR /app

# Install UV for fast dependency resolution
RUN pip install --no-cache-dir uv

# Copy all source code
COPY . .

# Install package and dependencies
RUN uv pip install --system --no-cache -e ".[dev]"

EXPOSE ${PORT:-8090}

CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8090}
