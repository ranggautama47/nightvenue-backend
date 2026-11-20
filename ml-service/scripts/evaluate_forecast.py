"""Run reproducible validation and held-out evaluation for synthetic forecasts."""

from datetime import date
from pathlib import Path

import numpy as np

from app.forecast import (
    DailyObservation,
    ForecastRequest,
    build_design_matrix,
    calculate_forecast,
    fit_and_predict_baseline,
    fit_and_predict_ols,
)
from scripts.generate_synthetic_forecast_data import (
    PROFILES,
    SEED,
    TOTAL_DAYS,
    generate_all,
)

HORIZON = 7
TRAINING_DAYS = (1, 84)
VALIDATION_DAYS = (85, 105)
HELD_OUT_DAYS = (106, 126)
VALIDATION_ORIGINS = (84, 91, 98)
HELD_OUT_ORIGINS = (105, 112, 119)
NOTICE = (
    "NOTICE: Evaluated using synthetic portfolio data generated with deterministic seeds. "
    "No real-world production accuracy is claimed or implied."
)


def _evaluate_windows(dataset: dict, origins: tuple[int, ...], split: str) -> list[dict]:
    results = []
    for name, item in dataset["profiles"].items():
        rows = item["rows"]
        for origin in origins:
            input_rows = rows[:origin]
            train_dates = [date.fromisoformat(row["businessDate"]) for row in input_rows]
            train_y = np.array([row["quantity"] for row in input_rows], dtype=float)
            actual_rows = rows[origin : origin + HORIZON]
            actual_dates = [date.fromisoformat(row["businessDate"]) for row in actual_rows]
            actual_y = np.array([row["quantity"] for row in actual_rows], dtype=float)
            request = ForecastRequest(
                ingredientId=ord(name) - ord("A") + 1,
                unit=item["unit"],
                horizonDays=HORIZON,
                series=[
                    DailyObservation(businessDate=d, quantity=float(y))
                    for d, y in zip(train_dates, train_y)
                ],
            )
            forecast = calculate_forecast(request)
            regression = fit_and_predict_ols(
                train_dates, train_y, actual_dates, train_dates[0]
            )
            baseline = fit_and_predict_baseline(train_dates, train_y, actual_dates)
            selected_predictions = (
                regression if forecast.selectedModel == "REGRESSION" else baseline
            )
            results.append(
                {
                    "split": split,
                    "ingredient": name,
                    "unit": item["unit"],
                    "origin": origin,
                    "selectedModel": forecast.selectedModel,
                    "predictedQuantity": forecast.predictedQuantity,
                    "actualQuantity": float(np.sum(actual_y)),
                    "modelMae": forecast.modelMae,
                    "baselineMae": forecast.baselineMae,
                    "regressionMae": float(np.mean(np.abs(actual_y - regression))),
                    "baselineOnlyMae": float(np.mean(np.abs(actual_y - baseline))),
                    "selectedMae": float(np.mean(np.abs(actual_y - selected_predictions))),
                    "totalQuantityAbsoluteError": abs(
                        forecast.predictedQuantity - float(np.sum(actual_y))
                    ),
                }
            )
    return results


def evaluate_validation(dataset: dict) -> list[dict]:
    return _evaluate_windows(dataset, VALIDATION_ORIGINS, "validation")


def evaluate_held_out(dataset: dict) -> list[dict]:
    return _evaluate_windows(dataset, HELD_OUT_ORIGINS, "held-out")


def evaluate_planted_vs_recovered(dataset: dict) -> list[dict]:
    recovered = []
    for name, item in dataset["profiles"].items():
        rows = item["rows"][: TRAINING_DAYS[1]]
        dates = [date.fromisoformat(row["businessDate"]) for row in rows]
        quantities = np.array([row["quantity"] for row in rows], dtype=float)
        design = build_design_matrix(dates, dates[0])
        coefficients, _, _, _ = np.linalg.lstsq(design, quantities, rcond=None)
        recovered_slope = float(coefficients[7])
        recovered.append(
            {
                "ingredient": name,
                "unit": item["unit"],
                "plantedSlope": item["planted"]["slope"],
                "recoveredSlope": recovered_slope,
            }
        )
    return recovered


def _aggregate(results: list[dict]) -> list[dict]:
    summary = []
    for name in PROFILES:
        rows = [row for row in results if row["ingredient"] == name]
        selected_counts = {
            model: sum(row["selectedModel"] == model for row in rows)
            for model in ("REGRESSION", "BASELINE")
        }
        summary.append(
            {
                "ingredient": name,
                "unit": rows[0]["unit"],
                "regressionMae": float(np.mean([row["regressionMae"] for row in rows])),
                "baselineMae": float(np.mean([row["baselineOnlyMae"] for row in rows])),
                "selectedMae": float(np.mean([row["selectedMae"] for row in rows])),
                "selectedCounts": selected_counts,
                "totalQuantityAbsoluteError": float(
                    sum(row["totalQuantityAbsoluteError"] for row in rows)
                ),
            }
        )
    return summary


def _fmt(value: float) -> str:
    return f"{value:.3f}"


def build_report(
    dataset: dict,
    validation_results: list[dict],
    held_out_results: list[dict],
    recovered: list[dict],
) -> str:
    profiles_text = ", ".join(
        "{} ({})".format(name, profile['unit'])
        for name, profile in PROFILES.items()
    )
    lines = [
        "# Evidence C — Inventory Forecast Evaluation",
        "",
        NOTICE,
        "",
        "## Dataset provenance",
        "",
        "- Generator: `ml-service/scripts/generate_synthetic_forecast_data.py`",
        f"- Seed: `{SEED}`; total days: `{TOTAL_DAYS}`; start: `2026-01-01`.",
        f"- Profiles: `{profiles_text}`.",
        f"- Dataset fingerprint (SHA-256): `{dataset['fingerprint']}`.",
        "",
        "## Split and windows",
        "",
        f"- Training: days {TRAINING_DAYS[0]}–{TRAINING_DAYS[1]}; validation: days "
        f"{VALIDATION_DAYS[0]}–{VALIDATION_DAYS[1]}; held-out: days "
        f"{HELD_OUT_DAYS[0]}–{HELD_OUT_DAYS[1]}.",
        f"- Horizon: {HORIZON} days; validation origins: "
        f"{', '.join(map(str, VALIDATION_ORIGINS))}; held-out origins: "
        f"{', '.join(map(str, HELD_OUT_ORIGINS))}.",
        "- Windows roll forward by the horizon with expanding input and no overlap.",
        "",
        "## Model definition",
        "",
        "- Regression: OLS weekday one-hot plus linear trend via `numpy.linalg.lstsq`.",
        "- Baseline: historical mean for the same weekday.",
        "- Selection: lower internal backtest MAE; ties select REGRESSION. "
        "No hyperparameters exist (OLS is closed form and the baseline is fixed).",
        "",
        "## Validation results",
        "",
        *_summary_lines(_aggregate(validation_results)),
        "",
        "## Held-out results (evaluated once)",
        "",
        *_summary_lines(_aggregate(held_out_results)),
        "",
        "### Held-out windows",
        "",
        *_window_lines(held_out_results),
        "",
        "## Planted vs recovered",
        "",
        "| Ingredient | Unit | Planted slope | Recovered OLS slope |",
        "|---|---|---:|---:|",
        *[
            _table_row(
                [
                    row["ingredient"],
                    row["unit"],
                    _fmt(row["plantedSlope"]),
                    _fmt(row["recoveredSlope"]),
                ]
            )
            for row in recovered
        ],
        "",
        "## Known limitations",
        "",
        "- Data is synthetic and generated from assumptions close to the OLS model, so regression "
        "winning here is expected and is NOT evidence of real-world accuracy.",
        "- Only three ingredients and three held-out windows are evaluated; no statistical "
        "significance is claimed.",
        "- There is no operating-day or closure modelling.",
        "- If the baseline wins on some series, that result is reported as is.",
        "",
    ]
    return "\n".join(lines)


def _summary_lines(summary: list[dict]) -> list[str]:
    lines = [
        _table_row(
            [
                "Ingredient",
                "Unit",
                "Regression mean daily MAE",
                "Baseline mean daily MAE",
                "Selected mean daily MAE",
                "Regression windows",
                "Baseline windows",
                "Total quantity absolute error",
            ]
        ),
        _table_row(["---", "---", "---:", "---:", "---:", "---:", "---:", "---:"]),
    ]
    lines.extend(
        _table_row(
            [
                row["ingredient"],
                row["unit"],
                _fmt(row["regressionMae"]),
                _fmt(row["baselineMae"]),
                _fmt(row["selectedMae"]),
                str(row["selectedCounts"]["REGRESSION"]),
                str(row["selectedCounts"]["BASELINE"]),
                _fmt(row["totalQuantityAbsoluteError"]),
            ]
        )
        for row in summary
    )
    return lines


def _window_lines(results: list[dict]) -> list[str]:
    lines = [
        _table_row(
            [
                "Ingredient",
                "Origin",
                "Selected",
                "Predicted total",
                "Actual total",
                "Internal model MAE",
                "Internal baseline MAE",
                "Regression daily MAE",
                "Baseline daily MAE",
                "Selected daily MAE",
            ]
        ),
        _table_row(["---", "---:", "---", "---:", "---:", "---:", "---:", "---:", "---:", "---:"]),
    ]
    lines.extend(
        _table_row(
            [
                row["ingredient"],
                str(row["origin"]),
                row["selectedModel"],
                _fmt(row["predictedQuantity"]),
                _fmt(row["actualQuantity"]),
                _fmt(row["modelMae"]),
                _fmt(row["baselineMae"]),
                _fmt(row["regressionMae"]),
                _fmt(row["baselineOnlyMae"]),
                _fmt(row["selectedMae"]),
            ]
        )
        for row in results
    )
    return lines


def _table_row(cells: list[str]) -> str:
    return f"| {' | '.join(cells)} |"


def main() -> None:
    dataset = generate_all()
    validation_results = evaluate_validation(dataset)
    held_out_results = evaluate_held_out(dataset)
    recovered = evaluate_planted_vs_recovered(dataset)
    report = build_report(dataset, validation_results, held_out_results, recovered)
    output_path = (
        Path(__file__).resolve().parents[1]
        / "reports"
        / "evidence_c_forecast_evaluation.md"
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report, encoding="utf-8")
    print(output_path)


if __name__ == "__main__":
    main()
