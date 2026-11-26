from __future__ import annotations

import inspect
import math
from fractions import Fraction
from types import SimpleNamespace

import pytest

from app.chat_evaluation import (
    FROZEN_CONFIG,
    FROZEN_CV_BEST_PARAMS,
    IdentityError,
    build_json_report,
    dumps_json_report,
    evaluate_raw,
    route_decision,
    select_threshold,
    selection_key,
    threshold_candidates,
    verify_identity,
)
from app.chat_text import INTENTS
from app.chat_training import dataset_fingerprint
from scripts.evaluate_chat_classifier import main


def _pred(intent: str, confidence: float) -> SimpleNamespace:
    return SimpleNamespace(intent=intent, confidence=confidence)


def _records(counts: dict[str, int], prefix: str = "r") -> list[dict]:
    rows: list[dict] = []
    i = 1
    for intent in INTENTS:
        for _ in range(counts.get(intent, 0)):
            rows.append(
                {
                    "id": f"{prefix}-{i}",
                    "text": f"text {prefix} {i}",
                    "intent": intent,
                    "lang": "en",
                    "source": "synthetic",
                }
            )
            i += 1
    return rows


def _valid_identity_kwargs() -> dict:
    train = _records({intent: 1 for intent in INTENTS}, prefix="train")
    val = _records({intent: 1 for intent in INTENTS}, prefix="val")
    probe_preds = [
        _pred("OPENING_HOURS", 0.5),
        _pred("OUT_OF_SCOPE", 0.75),
    ]
    expected_probe = (
        ("OPENING_HOURS", "0.500000"),
        ("OUT_OF_SCOPE", "0.750000"),
    )
    return {
        "meta": {
            "modelVersion": "m1",
            "train_fingerprint": dataset_fingerprint(train),
            "val_fingerprint": dataset_fingerprint(val),
            "config": dict(FROZEN_CONFIG),
            "cv_best_params": dict(FROZEN_CV_BEST_PARAMS),
        },
        "model_version": "m1",
        "class_labels": list(INTENTS),
        "train_records": train,
        "val_records": val,
        "probe_predictions": probe_preds,
        "expected_model_version": "m1",
        "expected_train_fingerprint": dataset_fingerprint(train),
        "expected_val_fingerprint": dataset_fingerprint(val),
        "expected_train_count": len(train),
        "expected_val_count": len(val),
        "expected_oos_count": 1,
        "expected_advanced_count": 1,
        "expected_local_count": 9,
        "expected_intents": list(INTENTS),
        "expected_config": dict(FROZEN_CONFIG),
        "expected_cv_best_params": dict(FROZEN_CV_BEST_PARAMS),
        "expected_probe": expected_probe,
    }


def test_route_decision_cases() -> None:
    # ADVANCED intent routes ADVANCED even at confidence 1.0
    assert route_decision("ADVANCED_CONVERSATION", 1.0, 0.5) == "ADVANCED"
    # confidence == theta passes (inclusive)
    assert route_decision("OPENING_HOURS", 0.5, 0.5) == "LOCAL"
    # just below theta falls back to ADVANCED
    assert (
        route_decision("OPENING_HOURS", math.nextafter(0.5, 0.0), 0.5)
        == "ADVANCED"
    )
    # OOS above theta -> REFUSED
    assert route_decision("OUT_OF_SCOPE", 0.9, 0.5) == "REFUSED"
    # invalid intent or confidence -> ValueError
    with pytest.raises(ValueError):
        route_decision("NOT_AN_INTENT", 0.9, 0.5)
    with pytest.raises(ValueError):
        route_decision("OPENING_HOURS", float("nan"), 0.5)
    with pytest.raises(ValueError):
        route_decision("OPENING_HOURS", 1.5, 0.5)
    with pytest.raises(ValueError):
        route_decision("OPENING_HOURS", 0.5, -0.1)


def test_threshold_candidates_properties() -> None:
    confidences = [0.2, 0.8, 0.8, 0.999]
    cands = threshold_candidates(confidences)
    assert cands[0] == 0.0
    assert cands[-1] == 1.0
    for c in confidences:
        assert c in cands
        if c < 1.0:
            assert math.nextafter(c, 1.0) in cands
    assert cands == sorted(cands)
    assert len(cands) == len(set(cands))
    assert all(value <= 1.0 for value in cands)


def test_oos_feasibility_boundary_48_records() -> None:
    # 48 OOS records: 44 refused feasible, 43 infeasible.
    true = ["OUT_OF_SCOPE"] * 48

    def run(refused_count: int):
        preds = [_pred("OUT_OF_SCOPE", 0.9)] * refused_count + [
            _pred("OPENING_HOURS", 0.9)
        ] * (48 - refused_count)
        return select_threshold(true, preds)

    feasible = run(44)
    infeasible = run(43)
    assert feasible["selected"] is not None
    assert feasible["selected"]["oos_tp"] == 44
    assert feasible["selected"]["oos_feasible"] is True
    assert infeasible["selected"] is None
    assert infeasible["best_achievable_oos_tp"] == 43
    assert infeasible["feasible_count"] == 0


def test_oos_feasibility_boundary_10_records_computed_from_data() -> None:
    # 10 OOS: 9 feasible, 8 infeasible (proves N_oos computed from data).
    true = ["OUT_OF_SCOPE"] * 10
    preds_9 = [_pred("OUT_OF_SCOPE", 0.9)] * 9 + [
        _pred("OPENING_HOURS", 0.9)
    ]
    preds_8 = [_pred("OUT_OF_SCOPE", 0.9)] * 8 + [
        _pred("OPENING_HOURS", 0.9)
    ] * 2
    sel_9 = select_threshold(true, preds_9)
    sel_8 = select_threshold(true, preds_8)
    assert sel_9["selected"] is not None
    assert sel_9["selected"]["oos_tp"] == 9
    assert sel_9["selected"]["oos_feasible"] is True
    assert sel_8["selected"] is None
    assert sel_8["best_achievable_oos_tp"] == 8


def test_selection_prefers_higher_local_macro_f1() -> None:
    # Two local classes correct at theta <= 0.5, both fallback above 0.5.
    true = ["OPENING_HOURS", "VENUE_AREA"] + ["OUT_OF_SCOPE"] * 10
    preds = [_pred("OPENING_HOURS", 0.5), _pred("VENUE_AREA", 0.5)] + [
        _pred("OUT_OF_SCOPE", 0.95)
    ] * 10
    sel = select_threshold(true, preds)
    assert sel["selected"] is not None
    assert sel["selected"]["local_macro_f1"] == Fraction(2, 9)
    assert sel["selected"]["theta"] == 0.5


def test_selection_tie_break_false_local_count() -> None:
    # Same local macro-F1, different false-local counts.
    true = ["OPENING_HOURS", "ADVANCED_CONVERSATION"] + ["OUT_OF_SCOPE"] * 10
    preds = [
        _pred("OPENING_HOURS", 0.9),
        _pred("MENU_INFORMATION", 0.6),
    ] + [_pred("OUT_OF_SCOPE", 0.95)] * 10
    sel = select_threshold(true, preds)
    assert sel["selected"] is not None
    # Need theta > 0.6 to avoid false-local from the predicted local intent.
    assert sel["selected"]["theta"] > 0.6
    assert sel["selected"]["false_local_count"] == 0
    assert sel["selected"]["local_macro_f1"] == Fraction(1, 9)


def test_selection_key_orders_each_stage() -> None:
    base = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 1,
        "correct_local_count": 1,
        "theta": 0.5,
    }
    higher_macro = {
        "local_macro_f1": Fraction(2, 9),
        "false_local_count": 2,
        "correct_local_count": 0,
        "theta": 0.1,
    }
    assert max(base, higher_macro, key=selection_key) == higher_macro

    fewer_false = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 0,
        "correct_local_count": 0,
        "theta": 0.1,
    }
    more_false = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 1,
        "correct_local_count": 2,
        "theta": 0.9,
    }
    assert max(fewer_false, more_false, key=selection_key) == fewer_false

    more_correct = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 0,
        "correct_local_count": 2,
        "theta": 0.1,
    }
    fewer_correct = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 0,
        "correct_local_count": 1,
        "theta": 0.9,
    }
    assert max(more_correct, fewer_correct, key=selection_key) == more_correct

    higher_theta = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 0,
        "correct_local_count": 1,
        "theta": 0.8,
    }
    lower_theta = {
        "local_macro_f1": Fraction(1, 9),
        "false_local_count": 0,
        "correct_local_count": 1,
        "theta": 0.2,
    }
    assert max(higher_theta, lower_theta, key=selection_key) == higher_theta


def test_selection_tie_break_highest_theta() -> None:
    # All other criteria equal, prefer highest theta.
    true = ["OPENING_HOURS"] + ["OUT_OF_SCOPE"] * 10
    preds = [_pred("OPENING_HOURS", 0.9)] + [_pred("OUT_OF_SCOPE", 0.95)] * 10
    sel = select_threshold(true, preds)
    assert sel["selected"] is not None
    assert sel["selected"]["local_macro_f1"] == Fraction(1, 9)
    assert sel["selected"]["false_local_count"] == 0
    assert sel["selected"]["correct_local_count"] == 1
    assert sel["selected"]["theta"] == 0.9


def test_infeasible_returns_none_and_exit_code_logic() -> None:
    true = ["OUT_OF_SCOPE"] * 10
    preds = [_pred("OPENING_HOURS", 0.9)] * 10
    sel = select_threshold(true, preds)
    assert sel["selected"] is None
    assert sel["feasible_count"] == 0
    assert sel["best_achievable_oos_tp"] == 0
    # CLI path gives exit 1 logic when no feasible theta is selected.
    exit_code = 0 if sel["selected"] is not None else 1
    assert exit_code == 1


def test_select_threshold_has_no_oos_target_parameter() -> None:
    sig = inspect.signature(select_threshold)
    params = list(sig.parameters.keys())
    assert "oos_target" not in params
    assert "target" not in params
    assert params == ["true_intents", "predictions"]


def test_verify_identity_passes_with_matching_parameters() -> None:
    verify_identity(**_valid_identity_kwargs())


def test_verify_identity_wrong_model_version() -> None:
    kwargs = _valid_identity_kwargs()
    kwargs["expected_model_version"] = "other"
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_verify_identity_wrong_fingerprint() -> None:
    kwargs = _valid_identity_kwargs()
    kwargs["expected_train_fingerprint"] = "0" * 64
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_verify_identity_wrong_count() -> None:
    kwargs = _valid_identity_kwargs()
    kwargs["expected_val_count"] = kwargs["expected_val_count"] + 1
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_verify_identity_wrong_label_set() -> None:
    kwargs = _valid_identity_kwargs()
    kwargs["class_labels"] = list(INTENTS[:-1]) + ["NOT_A_LABEL"]
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_verify_identity_wrong_config() -> None:
    kwargs = _valid_identity_kwargs()
    bad = dict(FROZEN_CONFIG)
    bad["seed"] = 7
    kwargs["expected_config"] = bad
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_verify_identity_wrong_cv_best_params() -> None:
    kwargs = _valid_identity_kwargs()
    kwargs["expected_cv_best_params"] = {"clf__C": 1.0}
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_verify_identity_wrong_probe_confidence_string() -> None:
    kwargs = _valid_identity_kwargs()
    kwargs["expected_probe"] = (
        ("OPENING_HOURS", "0.500001"),
        ("OUT_OF_SCOPE", "0.750000"),
    )
    with pytest.raises(IdentityError):
        verify_identity(**kwargs)


def test_main_missing_model_directory_returns_2_and_writes_no_reports(
    tmp_path,
) -> None:
    out_md = tmp_path / "out.md"
    out_json = tmp_path / "out.json"
    missing_model = tmp_path / "no_such_models"
    rc = main(
        [
            "--model-dir",
            str(missing_model),
            "--out-md",
            str(out_md),
            "--out-json",
            str(out_json),
        ]
    )
    assert rc == 2
    assert not out_md.exists()
    assert not out_json.exists()


def test_report_writer_determinism() -> None:
    true = list(INTENTS)
    preds = [_pred(intent, 0.9) for intent in INTENTS]
    raw = evaluate_raw(true, preds)
    sel = select_threshold(true, preds)
    counts = {
        "train": 11,
        "val": 11,
        "local": 9,
        "advanced_conversation": 1,
        "oos": 1,
    }
    report = build_json_report(
        model_version="m1",
        train_fingerprint="trainfp",
        val_fingerprint="valfp",
        counts=counts,
        status="SUCCESS" if sel["selected"] else "FAILURE",
        raw=raw,
        selected=sel["selected"],
        scan=sel["scan"],
        feasible_count=sel["feasible_count"],
        best_achievable_oos_tp=sel["best_achievable_oos_tp"],
        exploratory_baselines={
            "keyword": {
                "raw_accuracy": raw["accuracy"],
                "raw_macro_f1": raw["macro_f1"],
            },
            "length": {
                "raw_accuracy": raw["accuracy"],
                "raw_macro_f1": raw["macro_f1"],
            },
        },
    )
    b1 = dumps_json_report(report)
    b2 = dumps_json_report(report)
    assert b1 == b2
    text = b1.decode("utf-8")
    assert text.endswith("\n")
    assert not text.endswith("\n\n")
    # sorted keys: top-level keys appear in lexicographic order
    assert (
        text.index('"counts"')
        < text.index('"exploratory_baselines"')
        < text.index('"model_version"')
        < text.index('"status"')
    )
