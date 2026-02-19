# Dockerfile for AudioViz VJ Server
# Note: Audio capture requires Windows, this is for VJ server mode only

FROM python:3.12-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for caching
COPY pyproject.toml ./
COPY audio_processor/ ./audio_processor/
COPY python_client/ ./python_client/

# Install Python dependencies (minimal set for VJ server)
RUN pip install --no-cache-dir \
    numpy>=1.24.0 \
    scipy>=1.11.0 \
    websockets>=12.0 \
    python-dotenv>=1.0.0

# Copy application code
COPY admin_panel/ ./admin_panel/
COPY preview_tool/ ./preview_tool/
COPY configs/ ./configs/

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
ENTRYPOINT ["python", "-m", "audio_processor.vj_server"]
CMD ["--dj-port", "9000", "--broadcast-port", "8766", "--http-port", "8080"]
