from __future__ import annotations

from fractions import Fraction
from types import SimpleNamespace

import pytest

from app.chat_evaluation import evaluate_raw, evaluate_routed
from app.chat_text import INTENTS, LOCAL_INTENTS


def _pred(intent: str, confidence: float = 1.0) -> SimpleNamespace:
    return SimpleNamespace(intent=intent, confidence=confidence)


def test_raw_metrics_hand_computed_over_eleven_classes() -> None:
    true_intents = list(INTENTS)
    pred_intents = [
        "OPENING_HOURS",  # correct
        "MENU_INFORMATION",  # VENUE_AREA -> wrong
        "MENU_INFORMATION",  # correct
        "BOTTLE_KEEPING",  # correct
        "MINIMUM_SPEND",  # correct
        "PROMOTION",  # correct
        "TABLE_AVAILABILITY",  # correct
        "RESERVATION",  # correct
        "GENERAL_VENUE_INFORMATION",  # correct
        "OUT_OF_SCOPE",  # ADVANCED_CONVERSATION -> wrong
        "OUT_OF_SCOPE",  # correct
    ]
    preds = [_pred(intent) for intent in pred_intents]
    raw = evaluate_raw(true_intents, preds)

    assert raw["n_records"] == 11
    assert raw["accuracy"] == Fraction(9, 11)

    per = raw["per_class"]
    # VENUE_AREA: support 1, predicted as MENU_INFORMATION => TP 0, FP 0, FN 1
    assert per["VENUE_AREA"] == {
        "precision": Fraction(0, 1),
        "recall": Fraction(0, 1),
        "f1": Fraction(0, 1),
        "support": 1,
    }
    # MENU_INFORMATION: support 1, got extra FP from VENUE_AREA => TP 1, FP 1, FN 0
    assert per["MENU_INFORMATION"] == {
        "precision": Fraction(1, 2),
        "recall": Fraction(1, 1),
        "f1": Fraction(2, 3),
        "support": 1,
    }
    # ADVANCED_CONVERSATION: predicted as OUT_OF_SCOPE => all zero
    assert per["ADVANCED_CONVERSATION"] == {
        "precision": Fraction(0, 1),
        "recall": Fraction(0, 1),
        "f1": Fraction(0, 1),
        "support": 1,
    }
    # OUT_OF_SCOPE: TP 1, FP 1 (from ADVANCED_CONVERSATION), FN 0
    assert per["OUT_OF_SCOPE"] == {
        "precision": Fraction(1, 2),
        "recall": Fraction(1, 1),
        "f1": Fraction(2, 3),
        "support": 1,
    }
    # A perfectly predicted class
    assert per["OPENING_HOURS"] == {
        "precision": Fraction(1, 1),
        "recall": Fraction(1, 1),
        "f1": Fraction(1, 1),
        "support": 1,
    }

    # Macro-F1 over 11: 7 classes at 1, 2 classes at 2/3, 2 classes at 0
    assert raw["macro_f1"] == Fraction(25, 33)


def test_confusion_matrix_uses_intents_taxonomy_order() -> None:
    # Alphabetical order differs from INTENTS taxonomy order.
    # INTENTS starts at OPENING_HOURS and ends at OUT_OF_SCOPE.
    true_intents = ["OPENING_HOURS", "ADVANCED_CONVERSATION"]
    preds = [_pred("BOTTLE_KEEPING"), _pred("OUT_OF_SCOPE")]
    raw = evaluate_raw(true_intents, preds)

    labels = raw["confusion_matrix"]["labels"]
    matrix = raw["confusion_matrix"]["matrix"]
    assert labels == list(INTENTS)

    idx = {intent: i for i, intent in enumerate(INTENTS)}
    assert matrix[idx["OPENING_HOURS"]][idx["BOTTLE_KEEPING"]] == 1
    assert matrix[idx["ADVANCED_CONVERSATION"]][idx["OUT_OF_SCOPE"]] == 1
    assert sum(sum(row) for row in matrix) == 2

    # Alphabetical-order confusion would map rows/columns differently.
    alphabetical = sorted(INTENTS)
    assert alphabetical != list(INTENTS)
    # Taxonomy row 0 is OPENING_HOURS; alphabetical row 0 would differ.
    assert matrix[0][idx["BOTTLE_KEEPING"]] == 1
    alpha_idx = {intent: i for i, intent in enumerate(alphabetical)}
    assert alpha_idx["OPENING_HOURS"] != 0


def test_zero_denominator_class_gives_fraction_zero_without_exception() -> None:
    # A class with no support and no predictions has zero denominators.
    true_intents = ["OPENING_HOURS"]
    preds = [_pred("OPENING_HOURS")]
    raw = evaluate_raw(true_intents, preds)

    never_seen = raw["per_class"]["BOTTLE_KEEPING"]
    assert never_seen["support"] == 0
    assert never_seen["precision"] == Fraction(0, 1)
    assert never_seen["recall"] == Fraction(0, 1)
    assert never_seen["f1"] == Fraction(0, 1)


def test_routed_local_tp_fp_fn_and_low_confidence_fn() -> None:
    true_intents = [
        # predicted as VENUE_AREA, routed LOCAL -> FN for true, FP for predicted
        "OPENING_HOURS",
        # predicted correctly but low confidence -> FN
        "MENU_INFORMATION",
    ]
    preds = [
        _pred("VENUE_AREA", 0.9),
        _pred("MENU_INFORMATION", 0.2),
    ]
    routed = evaluate_routed(true_intents, preds, theta=0.5)

    opening = routed["per_class_local"]["OPENING_HOURS"]
    venue = routed["per_class_local"]["VENUE_AREA"]
    menu = routed["per_class_local"]["MENU_INFORMATION"]

    assert opening["support"] == 1
    assert opening["recall"] == Fraction(0, 1)
    assert opening["f1"] == Fraction(0, 1)

    assert venue["precision"] == Fraction(0, 1)
    assert venue["support"] == 0

    assert menu["support"] == 1
    assert menu["recall"] == Fraction(0, 1)
    assert menu["f1"] == Fraction(0, 1)
    assert routed["route_counts"]["ADVANCED"] == 1
    assert routed["route_counts"]["LOCAL"] == 1


def test_local_macro_f1_averages_exactly_nine_classes() -> None:
    # All 9 local intents present once and predicted correctly at high confidence.
    # Non-local classes must not affect the local macro-F1 average.
    true_intents = list(LOCAL_INTENTS) + [
        "ADVANCED_CONVERSATION",
        "OUT_OF_SCOPE",
    ]
    preds = [_pred(intent, 1.0) for intent in LOCAL_INTENTS] + [
        _pred("ADVANCED_CONVERSATION", 1.0),
        _pred("OUT_OF_SCOPE", 1.0),
    ]
    routed = evaluate_routed(true_intents, preds, theta=0.5)

    assert len(routed["per_class_local"]) == 9
    assert set(routed["per_class_local"].keys()) == set(LOCAL_INTENTS)
    for intent in LOCAL_INTENTS:
        assert routed["per_class_local"][intent]["f1"] == Fraction(1, 1)
    assert routed["local_macro_f1"] == Fraction(1, 1)


def test_false_local_counts_only_non_local_records_routed_local() -> None:
    true_intents = [
        "ADVANCED_CONVERSATION",  # predicted as local intent -> false-local
        "OUT_OF_SCOPE",  # predicted as local intent -> false-local
        "OUT_OF_SCOPE",  # predicted OUT_OF_SCOPE -> REFUSED
        "ADVANCED_CONVERSATION",  # predicted ADVANCED_CONVERSATION -> ADVANCED
        "OPENING_HOURS",  # wrong local intent but actual is local
    ]
    preds = [
        _pred("MENU_INFORMATION", 0.9),
        _pred("OPENING_HOURS", 0.9),
        _pred("OUT_OF_SCOPE", 0.9),
        _pred("ADVANCED_CONVERSATION", 0.9),
        _pred("MENU_INFORMATION", 0.9),
    ]
    routed = evaluate_routed(true_intents, preds, theta=0.5)

    assert routed["false_local_count"] == 2
    assert routed["n_nonlocal"] == 4
    assert routed["false_local_rate"] == Fraction(2, 4)
    assert routed["route_counts"]["REFUSED"] == 1
    assert routed["route_counts"]["ADVANCED"] == 1


def test_local_route_precision_requires_predicted_equals_actual() -> None:
    # 3 local records: 1 correct LOCAL, 1 wrong-intent LOCAL, 1 low-confidence.
    true_intents = ["OPENING_HOURS", "VENUE_AREA", "MENU_INFORMATION"]
    preds = [
        _pred("OPENING_HOURS", 0.9),  # correct local
        _pred("OPENING_HOURS", 0.9),  # wrong-intent local route
        _pred("MENU_INFORMATION", 0.1),  # low confidence -> ADVANCED
    ]
    routed = evaluate_routed(true_intents, preds, theta=0.5)

    assert routed["routed_local_total"] == 2
    assert routed["local_route_hits"] == 2
    assert routed["correct_local_count"] == 1
    assert routed["local_route_precision"] == Fraction(1, 2)
    assert routed["local_route_coverage"] == Fraction(2, 3)
    assert routed["correct_local_coverage"] == Fraction(1, 3)


def test_length_mismatch_raises_value_error() -> None:
    with pytest.raises(ValueError):
        evaluate_raw(["OPENING_HOURS"], [])
    with pytest.raises(ValueError):
        evaluate_routed(["OPENING_HOURS"], [], theta=0.5)
