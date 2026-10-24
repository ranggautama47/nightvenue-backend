# --- Build stage: install dependencies ---
FROM python:3.12.9-slim-bookworm AS build

WORKDIR /app

# Install build deps and create virtualenv
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# --- Runtime stage ---
FROM python:3.12.9-slim-bookworm

# Security: run as non-root
RUN groupadd --system --gid 1001 mlgroup && \
    useradd --system --uid 1001 --gid mlgroup mluser

# Copy pre-built virtualenv from build stage
COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /app

# Copy application code
COPY app/ ./app/

USER mluser

# Only expose internally — NOT published to host (see docker-compose.yml)
EXPOSE 8000

# Healthcheck using python -c HTTP probe (no curl/wget assumed)
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import urllib.request, sys; r = urllib.request.urlopen('http://localhost:8000/health', timeout=4); sys.exit(0 if r.status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
