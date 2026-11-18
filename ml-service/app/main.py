"""NightVenue ML Service.

Endpoints:
- GET /health: Liveness probe
- POST /ml/forecast/inventory: Inventory forecasting (Phase 9)
"""

from fastapi import Depends, FastAPI, status
from fastapi.responses import JSONResponse

from app.forecast import (
    ForecastRequest,
    ForecastResponse,
    ForecastValidationError,
    calculate_forecast,
)
from app.security import verify_internal_token

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


@app.post(
    "/ml/forecast/inventory",
    response_model=ForecastResponse,
    dependencies=[Depends(verify_internal_token)],
)
def forecast_inventory(request: ForecastRequest) -> ForecastResponse | JSONResponse:
    try:
        return calculate_forecast(request)
    except ForecastValidationError as exc:
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"code": exc.code, "message": "Forecast series is invalid"},
        )
