import math
from datetime import date, timedelta

from scripts.evaluate_forecast import (
    HELD_OUT_DAYS,
    HELD_OUT_ORIGINS,
    HORIZON,
    TRAINING_DAYS,
    VALIDATION_DAYS,
    VALIDATION_ORIGINS,
    build_report,
    evaluate_held_out,
    evaluate_planted_vs_recovered,
    evaluate_validation,
)
from scripts.generate_synthetic_forecast_data import (
    TOTAL_DAYS,
    generate_all,
)


def test_generate_all_is_deterministic() -> None:
    first = generate_all()
    second = generate_all()
    assert first["profiles"] == second["profiles"]
    assert first["fingerprint"] == second["fingerprint"]


def test_generated_rows_match_profile_constraints() -> None:
    dataset = generate_all()
    for item in dataset["profiles"].values():
        rows = item["rows"]
        assert len(rows) == TOTAL_DAYS
        dates = [date.fromisoformat(row["businessDate"]) for row in rows]
        assert all(
            (right - left) == timedelta(days=1)
            for left, right in zip(dates, dates[1:])
        )
        assert all(row["quantity"] >= 0 for row in rows)
        if item["unit"] == "PIECE":
            assert all(isinstance(row["quantity"], int) for row in rows)


def test_split_ranges_do_not_overlap_and_cover_dataset() -> None:
    ranges = (TRAINING_DAYS, VALIDATION_DAYS, HELD_OUT_DAYS)
    covered = [day for start, end in ranges for day in range(start, end + 1)]
    assert covered == list(range(1, TOTAL_DAYS + 1))
    assert max(TRAINING_DAYS) < min(VALIDATION_DAYS)
    assert max(VALIDATION_DAYS) < min(HELD_OUT_DAYS)
    assert VALIDATION_ORIGINS == (84, 91, 98)
    assert HELD_OUT_ORIGINS == (105, 112, 119)
    assert HORIZON == 7


def test_evaluator_returns_three_ingredients_by_three_held_out_windows() -> None:
    results = evaluate_held_out(generate_all())
    assert len(results) == 3 * len(HELD_OUT_ORIGINS)
    assert {row["ingredient"] for row in results} == {"A", "B", "C"}
    assert all(
        math.isfinite(row[key])
        for row in results
        for key in (
            "regressionMae",
            "baselineOnlyMae",
            "selectedMae",
            "modelMae",
            "baselineMae",
        )
    )


def test_report_builder_includes_notice_without_writing_report() -> None:
    dataset = generate_all()
    report = build_report(
        dataset,
        evaluate_validation(dataset),
        evaluate_held_out(dataset),
        evaluate_planted_vs_recovered(dataset),
    )
    assert "# Evidence C — Inventory Forecast Evaluation" in report
    assert (
        "NOTICE: Evaluated using synthetic portfolio data generated with deterministic seeds. "
        "No real-world production accuracy is claimed or implied."
    ) in report
