import json
from datetime import date, timedelta

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app

TOKEN = "test-internal-token-secret"


@pytest.fixture
def client(monkeypatch) -> TestClient:
    monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
    return TestClient(app)


def make_series(days: int, *, start: date = date(2026, 1, 1)) -> list[dict]:
    return [
        {
            "businessDate": (start + timedelta(days=i)).isoformat(),
            "quantity": float(100 + (i % 7) * 20 + i),
        }
        for i in range(days)
    ]


def post_forecast(client: TestClient, series: list[dict], **fields):
    payload = {"ingredientId": 1, "unit": "ML", "series": series, **fields}
    return client.post(
        "/ml/forecast/inventory",
        headers={"X-Internal-Token": TOKEN},
        json=payload,
    )


def _split(series: list[dict], horizon: int):
    dates = [date.fromisoformat(item["businessDate"]) for item in series]
    y = np.array([item["quantity"] for item in series], dtype=float)
    return dates[:-horizon], y[:-horizon], dates[-horizon:], y[-horizon:], dates[0]


def _expected_ols_mae(series: list[dict], horizon: int) -> float:
    from app.forecast import build_design_matrix

    train_dates, train_y, test_dates, test_y, start = _split(series, horizon)
    weights, _, _, _ = np.linalg.lstsq(
        build_design_matrix(train_dates, start), train_y, rcond=None
    )
    pred = np.maximum(0.0, build_design_matrix(test_dates, start) @ weights)
    return round(float(np.mean(np.abs(test_y - pred))), 3)


def _expected_baseline_mae(series: list[dict], horizon: int) -> float:
    from app.forecast import fit_and_predict_baseline

    train_dates, train_y, test_dates, test_y, _ = _split(series, horizon)
    pred = fit_and_predict_baseline(train_dates, train_y, test_dates)
    return round(float(np.mean(np.abs(test_y - pred))), 3)


def test_health_is_unauthenticated(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "ok", "service": "ml-service"}


def test_forecast_missing_token_returns_401(client: TestClient) -> None:
    res = client.post("/ml/forecast/inventory", json={})
    assert res.status_code == 401


def test_forecast_invalid_token_returns_401(client: TestClient) -> None:
    res = client.post(
        "/ml/forecast/inventory",
        headers={"X-Internal-Token": "wrong-token"},
        json={},
    )
    assert res.status_code == 401


def test_non_ascii_token_returns_401(client: TestClient) -> None:
    res = client.post(
        "/ml/forecast/inventory",
        headers={"X-Internal-Token": b"t\xc3\xb6k\xc3\xa9n"},
        json={},
    )
    assert res.status_code == 401


def test_missing_environment_token_fails_closed(monkeypatch) -> None:
    monkeypatch.delenv("ML_INTERNAL_TOKEN", raising=False)
    res = TestClient(app).post("/ml/forecast/inventory", json={})
    assert res.status_code == 401


def test_forecast_valid_series_returns_model_metadata(client: TestClient) -> None:
    res = post_forecast(client, make_series(28))
    assert res.status_code == 200
    data = res.json()
    assert data["horizonDays"] == 7
    assert data["inputDays"] == 28
    assert data["regressionModelName"] == "ols_weekday_trend"
    assert data["baselineModelName"] == "baseline_dow_mean"
    assert data["selectedModel"] in ("REGRESSION", "BASELINE")
    assert data["predictedQuantity"] >= 0


def test_forecast_insufficient_data_returns_structured_422(client: TestClient) -> None:
    res = post_forecast(client, make_series(27))
    assert res.status_code == 422
    assert res.json()["code"] == "INSUFFICIENT_DATA"
    assert "message" in res.json()


@pytest.mark.parametrize("bad_dates", ["descending", "duplicate", "gap"])
def test_invalid_date_order_or_gap_returns_422(
    client: TestClient, bad_dates: str
) -> None:
    series = make_series(28)
    if bad_dates == "descending":
        series.reverse()
    elif bad_dates == "duplicate":
        series[10]["businessDate"] = series[9]["businessDate"]
    else:
        series[10]["businessDate"] = (
            date.fromisoformat(series[10]["businessDate"]) + timedelta(days=1)
        ).isoformat()
    res = post_forecast(client, series)
    assert res.status_code == 422
    assert res.json()["code"] == "INVALID_SERIES"


def test_negative_quantity_is_rejected(client: TestClient) -> None:
    series = make_series(28)
    series[0]["quantity"] = -1
    assert post_forecast(client, series).status_code == 422


def test_infinite_quantity_is_rejected_in_raw_json(client: TestClient) -> None:
    series = make_series(28)
    series[0]["quantity"] = "Infinity"
    payload = {"ingredientId": 1, "unit": "ML", "series": series}
    res = client.post(
        "/ml/forecast/inventory",
        content=json.dumps(payload),
        headers={"X-Internal-Token": TOKEN, "Content-Type": "application/json"},
    )
    assert res.status_code == 422


def test_zero_consumption_is_stable(client: TestClient) -> None:
    series = make_series(28)
    for item in series:
        item["quantity"] = 0
    res = post_forecast(client, series)
    assert res.status_code == 200
    assert res.json()["predictedQuantity"] == 0
    assert res.json()["modelMae"] == 0
    assert res.json()["baselineMae"] == 0


def test_horizon_14_uses_14_day_holdout_with_35_inputs(client: TestClient) -> None:
    series = make_series(35)
    res = post_forecast(client, series, horizonDays=14)
    assert res.status_code == 200
    data = res.json()
    assert data["inputDays"] == 35
    assert data["modelMae"] == pytest.approx(_expected_ols_mae(series, 14))
    assert data["baselineMae"] == pytest.approx(_expected_baseline_mae(series, 14))


def test_horizon_14_with_28_inputs_returns_422(client: TestClient) -> None:
    res = post_forecast(client, make_series(28), horizonDays=14)
    assert res.status_code == 422
    assert res.json()["code"] == "INSUFFICIENT_DATA"


@pytest.mark.parametrize("horizon", [0, 15])
def test_horizon_out_of_range_returns_422(client: TestClient, horizon: int) -> None:
    res = post_forecast(client, make_series(35), horizonDays=horizon)
    assert res.status_code == 422


def test_weekday_trend_pattern_selects_regression(client: TestClient) -> None:
    data = post_forecast(client, make_series(35)).json()
    assert data["selectedModel"] == "REGRESSION"
    assert data["modelName"] == "ols_weekday_trend"
    assert data["modelMae"] < data["baselineMae"]


def test_baseline_selected_when_regression_is_worse(
    client: TestClient, monkeypatch
) -> None:
    import app.forecast as fc

    def bad_ols(train_dates, train_y, pred_dates, start_date):
        return np.full(len(pred_dates), 1e9)

    monkeypatch.setattr(fc, "fit_and_predict_ols", bad_ols)
    data = post_forecast(client, make_series(35)).json()
    assert data["selectedModel"] == "BASELINE"
    assert data["modelName"] == "baseline_dow_mean"
    assert data["baselineMae"] < data["modelMae"]


def test_tie_prefers_regression(client: TestClient, monkeypatch) -> None:
    import app.forecast as fc

    def ols_equals_baseline(train_dates, train_y, pred_dates, start_date):
        return fc.fit_and_predict_baseline(train_dates, train_y, pred_dates)

    monkeypatch.setattr(fc, "fit_and_predict_ols", ols_equals_baseline)
    data = post_forecast(client, make_series(35)).json()
    assert data["modelMae"] == data["baselineMae"]
    assert data["selectedModel"] == "REGRESSION"
