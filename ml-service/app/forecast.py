from datetime import date, timedelta
from typing import Literal

import numpy as np
from pydantic import BaseModel, Field


class DailyObservation(BaseModel):
    businessDate: date
    quantity: float = Field(ge=0.0, allow_inf_nan=False)


class ForecastRequest(BaseModel):
    ingredientId: int
    unit: Literal["ML", "GRAM", "PIECE"]
    horizonDays: int = Field(default=7, ge=1, le=14)
    series: list[DailyObservation]


class ForecastResponse(BaseModel):
    ingredientId: int
    unit: str
    horizonDays: int
    predictedQuantity: float
    selectedModel: Literal["REGRESSION", "BASELINE"]
    modelName: str
    modelMae: float
    baselineMae: float
    inputDays: int
    asOfBusinessDate: date
    regressionModelName: str
    baselineModelName: str


class ForecastValidationError(Exception):
    def __init__(self, code: str):
        self.code = code


def validate_series(series: list[DailyObservation], horizon: int) -> None:
    if len(series) < max(28, 21 + horizon):
        raise ForecastValidationError("INSUFFICIENT_DATA")
    dates = [item.businessDate for item in series]
    if any((right - left).days != 1 for left, right in zip(dates, dates[1:])):
        raise ForecastValidationError("INVALID_SERIES")


def build_design_matrix(dates: list[date], start_date: date) -> np.ndarray:
    """Build design matrix with day-of-week one-hot (7 cols) + linear trend (1 col)."""
    rows = []
    for d in dates:
        row = [0.0] * 8
        row[d.weekday()] = 1.0
        row[7] = float((d - start_date).days)
        rows.append(row)
    return np.array(rows, dtype=float)


def fit_and_predict_ols(
    train_dates: list[date],
    train_y: np.ndarray,
    pred_dates: list[date],
    start_date: date,
) -> np.ndarray:
    """Fit OLS via SVD lstsq and predict for pred_dates, clipping negative values to 0."""
    x_train = build_design_matrix(train_dates, start_date)
    weights, _, _, _ = np.linalg.lstsq(x_train, train_y, rcond=None)
    x_pred = build_design_matrix(pred_dates, start_date)
    preds = x_pred @ weights
    return np.maximum(0.0, preds)


def fit_and_predict_baseline(
    train_dates: list[date],
    train_y: np.ndarray,
    pred_dates: list[date],
) -> np.ndarray:
    """Baseline: mean of the same day-of-week across the training window."""
    overall_mean = float(np.mean(train_y)) if len(train_y) > 0 else 0.0
    dow_means = {}
    for dow in range(7):
        mask = [d.weekday() == dow for d in train_dates]
        if any(mask):
            dow_means[dow] = float(np.mean(train_y[mask]))
        else:
            dow_means[dow] = overall_mean

    preds = [max(0.0, dow_means[d.weekday()]) for d in pred_dates]
    return np.array(preds, dtype=float)


def calculate_forecast(req: ForecastRequest) -> ForecastResponse:
    validate_series(req.series, req.horizonDays)
    n = len(req.series)
    dates = [x.businessDate for x in req.series]
    quantities = np.array([x.quantity for x in req.series], dtype=float)
    as_of = dates[-1]

    test_len = req.horizonDays
    train_dates = dates[:-test_len]
    train_y = quantities[:-test_len]
    test_dates = dates[-test_len:]
    test_y = quantities[-test_len:]

    start_date = dates[0]

    # 1. Backtest OLS
    ols_test_preds = fit_and_predict_ols(train_dates, train_y, test_dates, start_date)
    model_mae = float(np.mean(np.abs(test_y - ols_test_preds)))

    # 2. Backtest Baseline
    baseline_test_preds = fit_and_predict_baseline(train_dates, train_y, test_dates)
    baseline_mae = float(np.mean(np.abs(test_y - baseline_test_preds)))

    # Selection: lower MAE wins. Tie breaks to REGRESSION
    if model_mae <= baseline_mae:
        selected_model = "REGRESSION"
        model_name = "ols_weekday_trend"
    else:
        selected_model = "BASELINE"
        model_name = "baseline_dow_mean"

    # Forward horizon projection dates
    horizon_dates = [as_of + timedelta(days=i) for i in range(1, req.horizonDays + 1)]

    # Refit winning model on all N days of data
    if selected_model == "REGRESSION":
        forward_preds = fit_and_predict_ols(dates, quantities, horizon_dates, start_date)
    else:
        forward_preds = fit_and_predict_baseline(dates, quantities, horizon_dates)

    total_predicted = float(np.sum(forward_preds))

    return ForecastResponse(
        ingredientId=req.ingredientId,
        unit=req.unit,
        horizonDays=req.horizonDays,
        predictedQuantity=round(total_predicted, 3),
        selectedModel=selected_model,
        modelName=model_name,
        modelMae=round(model_mae, 3),
        baselineMae=round(baseline_mae, 3),
        inputDays=n,
        asOfBusinessDate=as_of,
        regressionModelName="ols_weekday_trend",
        baselineModelName="baseline_dow_mean",
    )
