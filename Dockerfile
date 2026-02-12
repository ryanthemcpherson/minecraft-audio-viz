# Dockerfile for MCAV VJ Server
# Note: Audio capture requires Windows, this is for VJ server mode only

FROM python:3.12-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Copy package definitions first for caching
COPY vj_server/pyproject.toml ./vj_server/pyproject.toml

# Install Python dependencies
RUN pip install --no-cache-dir ./vj_server[full]

# Copy application code
COPY vj_server/ ./vj_server/
COPY python_client/ ./python_client/
COPY admin_panel/ ./admin_panel/
COPY preview_tool/ ./preview_tool/
COPY configs/ ./configs/

# Reinstall with source (editable not needed in container)
RUN pip install --no-cache-dir ./vj_server[full]

# Create non-root user
RUN useradd -m -u 1000 audioviz
USER audioviz

# Expose ports
# 9000 - DJ connections
# 8766 - Browser clients
# 8080 - HTTP admin panel
EXPOSE 9000 8766 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD python -c "import socket; s=socket.socket(); s.connect(('localhost', 9000)); s.close()"

# Default command: VJ server mode
ENTRYPOINT ["audioviz-vj"]
CMD ["--dj-port", "9000", "--broadcast-port", "8766", "--http-port", "8080"]
