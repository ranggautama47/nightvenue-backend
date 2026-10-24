"""NightVenue ML Service — Phase 1 skeleton.

Only GET /health is implemented.
ML endpoints (/ml/chat/classify, /ml/forecast/inventory) are deferred to Phase 9.
Authentication is deferred to Phase 3.
"""

from fastapi import FastAPI

app = FastAPI(
    title="NightVenue ML Service",
    description="Internal FastAPI service for ML classification and forecasting.",
    version="0.1.0",
    docs_url="/docs",
    openapi_url="/openapi.json",
)


@app.get("/health")
def health() -> dict:
    """Liveness probe — no external dependencies checked here."""
    return {"status": "ok", "service": "ml-service"}
