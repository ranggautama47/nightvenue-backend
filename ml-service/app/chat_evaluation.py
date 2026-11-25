"""Validation evaluation and threshold selection for the chat intent classifier.

Pure logic for routing, metrics, threshold scanning, identity checks, and
report rendering. No timestamps, no absolute paths, no randomness.
"""

from __future__ import annotations

import json
import math
from fractions import Fraction
from pathlib import Path
from typing import Any, Sequence

from app.chat_text import INTENTS, LOCAL_INTENTS
from app.chat_training import dataset_fingerprint

ROUTES = ("LOCAL", "ADVANCED", "REFUSED")
LOCAL_INTENT_SET = set(LOCAL_INTENTS)
NONLOCAL_INTENTS = {"ADVANCED_CONVERSATION", "OUT_OF_SCOPE"}

# Frozen Model K effective configuration (exact training config stored in meta).
FROZEN_CONFIG = {
    "seed": 42,
    "c": 10.0,
    "max_iter": 3000,
    "solver": "lbfgs",
    "features": {
        "char_wb": {
            "analyzer": "char_wb",
            "ngram_range": [2, 4],
            "sublinear_tf": True,
        },
        "word": {
            "analyzer": "word",
            "ngram_range": [1, 2],
            "sublinear_tf": True,
            "token_pattern": r"(?u)\b\w+\b",
        },
    },
    "class_weight": "(N/(K*count_c))**0.5",
    "cv": "none",
    "scoring": "fixed",
}
FROZEN_CV_BEST_PARAMS = {"clf__C": 10.0}


class IdentityError(RuntimeError):
    """Raised when the frozen model/data identity checks fail."""


def frac(numerator: int, denominator: int) -> Fraction:
    """Return numerator/denominator, or Fraction(0) when denominator is zero."""
    if denominator == 0:
        return Fraction(0, 1)
    return Fraction(numerator, denominator)


def frac_to_json(value: Fraction) -> dict[str, str]:
    """Serialize a Fraction for deterministic JSON reports."""
    return {
        "fraction": f"{value.numerator}/{value.denominator}",
        "value": f"{float(value):.6f}",
    }


def _is_finite_unit_interval(value: float) -> bool:
    return math.isfinite(value) and 0.0 <= value <= 1.0


def route_decision(intent: str, confidence: float, theta: float) -> str:
    """Route a prediction according to the single-theta policy."""
    if intent not in INTENTS:
        raise ValueError(f"Unknown intent: {intent!r}")
    if not _is_finite_unit_interval(confidence):
        raise ValueError(f"Invalid confidence: {confidence!r}")
    if not _is_finite_unit_interval(theta):
        raise ValueError(f"Invalid theta: {theta!r}")

    if intent == "ADVANCED_CONVERSATION":
        return "ADVANCED"
    if confidence < theta:
        return "ADVANCED"
    if intent == "OUT_OF_SCOPE":
        return "REFUSED"
    if intent in LOCAL_INTENT_SET:
        return "LOCAL"
    raise ValueError(f"Unhandled intent: {intent!r}")


def _validate_lengths(true_intents: Sequence[str], predictions: Sequence[Any]) -> None:
    if len(true_intents) != len(predictions):
        raise ValueError(
            "Length mismatch: "
            f"{len(true_intents)} labels vs {len(predictions)} predictions"
        )


def _intent_of(prediction: Any) -> str:
    if isinstance(prediction, dict):
        return prediction["intent"]
    return prediction.intent


def _confidence_of(prediction: Any) -> float:
    if isinstance(prediction, dict):
        return float(prediction["confidence"])
    return float(prediction.confidence)


def _prf(tp: int, fp: int, fn: int) -> dict[str, Any]:
    precision = frac(tp, tp + fp)
    recall = frac(tp, tp + fn)
    f1 = frac(2 * tp, 2 * tp + fp + fn)
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def evaluate_raw(true_intents: Sequence[str], predictions: Sequence[Any]) -> dict[str, Any]:
    """Compute raw 11-class metrics without routing."""
    _validate_lengths(true_intents, predictions)
    n = len(true_intents)
    correct = 0
    counts: dict[str, dict[str, int]] = {}
    for intent in INTENTS:
        counts[intent] = {"tp": 0, "fp": 0, "fn": 0, "support": 0}

    matrix = [[0 for _ in INTENTS] for _ in INTENTS]
    index = {intent: i for i, intent in enumerate(INTENTS)}

    for actual, pred in zip(true_intents, predictions):
        predicted = _intent_of(pred)
        if actual not in index or predicted not in index:
            raise ValueError(
                f"Unknown intent in evaluation: {actual!r}, {predicted!r}"
            )
        matrix[index[actual]][index[predicted]] += 1
        counts[actual]["support"] += 1
        if actual == predicted:
            correct += 1
            counts[actual]["tp"] += 1
        else:
            counts[actual]["fn"] += 1
            counts[predicted]["fp"] += 1

    per_class: dict[str, Any] = {}
    f1_sum = Fraction(0, 1)
    for intent in INTENTS:
        stats = counts[intent]
        prf = _prf(stats["tp"], stats["fp"], stats["fn"])
        per_class[intent] = {
            "precision": prf["precision"],
            "recall": prf["recall"],
            "f1": prf["f1"],
            "support": stats["support"],
        }
        f1_sum += prf["f1"]

    macro_f1 = frac(f1_sum.numerator, f1_sum.denominator * len(INTENTS))
    accuracy = frac(correct, n)
    return {
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "per_class": per_class,
        "confusion_matrix": {
            "labels": list(INTENTS),
            "matrix": matrix,
        },
        "n_records": n,
    }


def evaluate_routed(
    true_intents: Sequence[str], predictions: Sequence[Any], theta: float
) -> dict[str, Any]:
    """Compute routed metrics at a fixed threshold."""
    _validate_lengths(true_intents, predictions)
    if not _is_finite_unit_interval(theta):
        raise ValueError(f"Invalid theta: {theta!r}")

    n_local = 0
    n_oos = 0
    n_nonlocal = 0
    route_counts = {route: 0 for route in ROUTES}
    low_confidence_fallback_count = 0
    oos_tp = 0
    false_local_count = 0
    routed_local_total = 0
    local_route_hits = 0
    correct_local_count = 0
    oos_to_advanced = 0
    oos_to_local_intent = 0
    non_oos_routed_advanced = 0
    non_oos_routed_refused = 0
    refused_total = 0
    refused_true_oos = 0

    local_counts = {
        intent: {"tp": 0, "fp": 0, "support": 0} for intent in LOCAL_INTENTS
    }

    for actual, pred in zip(true_intents, predictions):
        predicted = _intent_of(pred)
        confidence = _confidence_of(pred)
        route = route_decision(predicted, confidence, theta)

        route_counts[route] += 1
        if predicted != "ADVANCED_CONVERSATION" and confidence < theta:
            low_confidence_fallback_count += 1

        if actual in LOCAL_INTENT_SET:
            n_local += 1
            local_counts[actual]["support"] += 1
        if actual == "OUT_OF_SCOPE":
            n_oos += 1
        if actual in NONLOCAL_INTENTS:
            n_nonlocal += 1

        if route == "LOCAL":
            routed_local_total += 1
            if actual in LOCAL_INTENT_SET:
                local_route_hits += 1
                if predicted == actual:
                    correct_local_count += 1
            if actual in LOCAL_INTENT_SET and predicted == actual:
                local_counts[actual]["tp"] += 1
            elif predicted in LOCAL_INTENT_SET:
                local_counts[predicted]["fp"] += 1

        if route == "REFUSED":
            refused_total += 1
            if actual == "OUT_OF_SCOPE":
                refused_true_oos += 1
            else:
                non_oos_routed_refused += 1

        if route == "ADVANCED" and actual != "OUT_OF_SCOPE":
            non_oos_routed_advanced += 1

        if actual == "OUT_OF_SCOPE":
            if route == "REFUSED":
                oos_tp += 1
            elif route == "ADVANCED":
                oos_to_advanced += 1
            elif route == "LOCAL":
                oos_to_local_intent += 1

        if actual in NONLOCAL_INTENTS and route == "LOCAL":
            false_local_count += 1

    per_class_local: dict[str, Any] = {}
    f1_sum = Fraction(0, 1)
    for intent in LOCAL_INTENTS:
        tp = local_counts[intent]["tp"]
        fp = local_counts[intent]["fp"]
        fn = local_counts[intent]["support"] - tp
        prf = _prf(tp, fp, fn)
        per_class_local[intent] = {
            "precision": prf["precision"],
            "recall": prf["recall"],
            "f1": prf["f1"],
            "support": local_counts[intent]["support"],
        }
        f1_sum += prf["f1"]

    local_macro_f1 = frac(
        f1_sum.numerator, f1_sum.denominator * len(LOCAL_INTENTS)
    )
    oos_recall = frac(oos_tp, n_oos)
    oos_precision = frac(refused_true_oos, refused_total)
    oos_feasible = oos_tp * 10 >= 9 * n_oos
    false_local_rate = frac(false_local_count, n_nonlocal)
    local_route_coverage = frac(local_route_hits, n_local)
    correct_local_coverage = frac(correct_local_count, n_local)
    local_route_precision = frac(correct_local_count, routed_local_total)
    non_oos_count = len(true_intents) - n_oos
    non_oos_advanced_share = frac(non_oos_routed_advanced, non_oos_count)
    over_refusal_count = non_oos_routed_refused
    over_refusal_rate = frac(over_refusal_count, non_oos_count)

    return {
        "theta": theta,
        "local_macro_f1": local_macro_f1,
        "per_class_local": per_class_local,
        "oos_tp": oos_tp,
        "oos_recall": oos_recall,
        "oos_precision": oos_precision,
        "oos_feasible": oos_feasible,
        "false_local_count": false_local_count,
        "false_local_rate": false_local_rate,
        "routed_local_total": routed_local_total,
        "local_route_hits": local_route_hits,
        "correct_local_count": correct_local_count,
        "local_route_coverage": local_route_coverage,
        "correct_local_coverage": correct_local_coverage,
        "local_route_precision": local_route_precision,
        "route_counts": route_counts,
        "low_confidence_fallback_count": low_confidence_fallback_count,
        "n_local": n_local,
        "n_nonlocal": n_nonlocal,
        "n_oos": n_oos,
        "oos_to_advanced": oos_to_advanced,
        "oos_to_local_intent": oos_to_local_intent,
        "non_oos_routed_advanced": non_oos_routed_advanced,
        "non_oos_advanced_share": non_oos_advanced_share,
        "over_refusal_count": over_refusal_count,
        "over_refusal_rate": over_refusal_rate,
        "non_oos_count": non_oos_count,
    }


def threshold_candidates(confidences: Sequence[float]) -> list[float]:
    """Build the deterministic candidate threshold set."""
    values = {0.0, 1.0}
    for confidence in confidences:
        if not math.isfinite(confidence):
            raise ValueError(f"Invalid confidence: {confidence!r}")
        values.add(float(confidence))
        if confidence < 1.0:
            bumped = math.nextafter(float(confidence), 1.0)
            if bumped <= 1.0:
                values.add(bumped)
    return sorted(values)


def _scan_entry(routed: dict[str, Any]) -> dict[str, Any]:
    return {
        "theta": routed["theta"],
        "feasible": routed["oos_feasible"],
        "oos_tp": routed["oos_tp"],
        "local_macro_f1": routed["local_macro_f1"],
        "false_local_count": routed["false_local_count"],
        "correct_local_count": routed["correct_local_count"],
    }


def selection_key(item: dict[str, Any]) -> tuple:
    return (
        item["local_macro_f1"],
        -item["false_local_count"],
        item["correct_local_count"],
        item["theta"],
    )


def select_threshold(
    true_intents: Sequence[str], predictions: Sequence[Any]
) -> dict[str, Any]:
    """Scan thresholds and select the best feasible candidate."""
    _validate_lengths(true_intents, predictions)
    confidences = [_confidence_of(prediction) for prediction in predictions]
    candidates = threshold_candidates(confidences)

    scan: list[dict[str, Any]] = []
    feasible: list[dict[str, Any]] = []
    best_oos_tp = 0
    for theta in candidates:
        routed = evaluate_routed(true_intents, predictions, theta)
        entry = _scan_entry(routed)
        scan.append(entry)
        best_oos_tp = max(best_oos_tp, routed["oos_tp"])
        if routed["oos_feasible"]:
            feasible.append(routed)

    selected = None
    highest_feasible_theta = None
    if feasible:
        selected = max(feasible, key=selection_key)
        highest_feasible_theta = max(r["theta"] for r in feasible)

    return {
        "candidates": candidates,
        "scan": scan,
        "feasible_count": len(feasible),
        "best_achievable_oos_tp": best_oos_tp,
        "selected": selected,
        "highest_feasible_theta": highest_feasible_theta,
    }


def _assert_equal(actual: Any, expected: Any, label: str) -> None:
    if actual != expected:
        raise IdentityError(
            f"Identity mismatch for {label}: "
            f"expected {expected!r}, got {actual!r}"
        )


def verify_identity(
    *,
    meta: dict[str, Any],
    model_version: str,
    class_labels: Sequence[str],
    train_records: Sequence[dict],
    val_records: Sequence[dict],
    probe_predictions: Sequence[Any],
    expected_model_version: str,
    expected_train_fingerprint: str,
    expected_val_fingerprint: str,
    expected_train_count: int,
    expected_val_count: int,
    expected_oos_count: int,
    expected_advanced_count: int,
    expected_local_count: int,
    expected_intents: Sequence[str],
    expected_config: dict[str, Any],
    expected_cv_best_params: dict[str, Any],
    expected_probe: Sequence[tuple[str, str]],
) -> None:
    """Verify frozen model and dataset identity from plain data."""
    _assert_equal(model_version, expected_model_version, "model_version")
    _assert_equal(
        meta.get("modelVersion"), expected_model_version, "meta.modelVersion"
    )

    train_fp = dataset_fingerprint(train_records)
    val_fp = dataset_fingerprint(val_records)
    _assert_equal(train_fp, expected_train_fingerprint, "train_fingerprint")
    _assert_equal(val_fp, expected_val_fingerprint, "val_fingerprint")
    _assert_equal(
        meta.get("train_fingerprint"),
        expected_train_fingerprint,
        "meta.train_fingerprint",
    )
    _assert_equal(
        meta.get("val_fingerprint"),
        expected_val_fingerprint,
        "meta.val_fingerprint",
    )

    train_count = len(train_records)
    val_count = len(val_records)
    oos_count = sum(
        1 for record in val_records if record.get("intent") == "OUT_OF_SCOPE"
    )
    advanced_count = sum(
        1
        for record in val_records
        if record.get("intent") == "ADVANCED_CONVERSATION"
    )
    local_count = sum(
        1
        for record in val_records
        if record.get("intent") in LOCAL_INTENT_SET
    )
    _assert_equal(train_count, expected_train_count, "train_count")
    _assert_equal(val_count, expected_val_count, "val_count")
    _assert_equal(oos_count, expected_oos_count, "oos_count")
    _assert_equal(advanced_count, expected_advanced_count, "advanced_count")
    _assert_equal(local_count, expected_local_count, "local_count")

    labels = list(class_labels)
    if set(labels) != set(expected_intents) or len(labels) != len(expected_intents):
        raise IdentityError(
            "Identity mismatch for class_labels: "
            f"expected {sorted(expected_intents)!r}, got {sorted(labels)!r}"
        )

    _assert_equal(meta.get("config"), expected_config, "meta.config")
    _assert_equal(
        meta.get("cv_best_params"),
        expected_cv_best_params,
        "meta.cv_best_params",
    )

    if len(probe_predictions) != len(expected_probe):
        raise IdentityError(
            "Identity mismatch for probe length: "
            f"expected {len(expected_probe)}, got {len(probe_predictions)}"
        )
    for index, (prediction, (expected_intent, expected_conf)) in enumerate(
        zip(probe_predictions, expected_probe), start=1
    ):
        actual_intent = _intent_of(prediction)
        actual_conf = f"{_confidence_of(prediction):.6f}"
        _assert_equal(actual_intent, expected_intent, f"probe[{index}].intent")
        _assert_equal(actual_conf, expected_conf, f"probe[{index}].confidence")


def _json_default(obj: Any) -> Any:
    if isinstance(obj, Fraction):
        return frac_to_json(obj)
    raise TypeError(
        f"Object of type {type(obj).__name__} is not JSON serializable"
    )


def _raw_to_json(raw: dict[str, Any]) -> dict[str, Any]:
    per_class = {}
    for intent, stats in raw["per_class"].items():
        per_class[intent] = {
            "precision": frac_to_json(stats["precision"]),
            "recall": frac_to_json(stats["recall"]),
            "f1": frac_to_json(stats["f1"]),
            "support": stats["support"],
        }
    return {
        "accuracy": frac_to_json(raw["accuracy"]),
        "confusion_matrix": raw["confusion_matrix"],
        "macro_f1": frac_to_json(raw["macro_f1"]),
        "n_records": raw["n_records"],
        "per_class": per_class,
    }


def _routed_to_json(routed: dict[str, Any] | None) -> dict[str, Any] | None:
    if routed is None:
        return None
    per_class_local = {}
    for intent, stats in routed["per_class_local"].items():
        per_class_local[intent] = {
            "precision": frac_to_json(stats["precision"]),
            "recall": frac_to_json(stats["recall"]),
            "f1": frac_to_json(stats["f1"]),
            "support": stats["support"],
        }
    return {
        "theta": routed["theta"],
        "local_macro_f1": frac_to_json(routed["local_macro_f1"]),
        "per_class_local": per_class_local,
        "oos_tp": routed["oos_tp"],
        "oos_recall": frac_to_json(routed["oos_recall"]),
        "oos_precision": frac_to_json(routed["oos_precision"]),
        "oos_feasible": routed["oos_feasible"],
        "false_local_count": routed["false_local_count"],
        "false_local_rate": frac_to_json(routed["false_local_rate"]),
        "routed_local_total": routed["routed_local_total"],
        "local_route_hits": routed["local_route_hits"],
        "correct_local_count": routed["correct_local_count"],
        "local_route_coverage": frac_to_json(routed["local_route_coverage"]),
        "correct_local_coverage": frac_to_json(
            routed["correct_local_coverage"]
        ),
        "local_route_precision": frac_to_json(
            routed["local_route_precision"]
        ),
        "route_counts": routed["route_counts"],
        "low_confidence_fallback_count": routed[
            "low_confidence_fallback_count"
        ],
        "n_local": routed["n_local"],
        "n_nonlocal": routed["n_nonlocal"],
        "n_oos": routed["n_oos"],
        "oos_to_advanced": routed["oos_to_advanced"],
        "oos_to_local_intent": routed["oos_to_local_intent"],
        "non_oos_routed_advanced": routed["non_oos_routed_advanced"],
        "non_oos_advanced_share": frac_to_json(
            routed["non_oos_advanced_share"]
        ),
        "over_refusal_count": routed["over_refusal_count"],
        "over_refusal_rate": frac_to_json(routed["over_refusal_rate"]),
        "non_oos_count": routed["non_oos_count"],
    }


def build_json_report(
    *,
    model_version: str,
    train_fingerprint: str,
    val_fingerprint: str,
    counts: dict[str, int],
    status: str,
    raw: dict[str, Any],
    selected: dict[str, Any] | None,
    scan: list[dict[str, Any]],
    feasible_count: int,
    best_achievable_oos_tp: int,
    exploratory_baselines: dict[str, Any],
    highest_feasible_theta: float | None = None,
    determinism_evidence: str | None = None,
) -> dict[str, Any]:
    """Build the JSON-serializable metrics report."""
    scan_json = []
    for entry in scan:
        scan_json.append(
            {
                "theta": entry["theta"],
                "feasible": entry["feasible"],
                "oos_tp": entry["oos_tp"],
                "local_macro_f1": frac_to_json(entry["local_macro_f1"]),
                "false_local_count": entry["false_local_count"],
                "correct_local_count": entry["correct_local_count"],
            }
        )
    selected_non_oos_advanced_share = None
    if selected is not None:
        selected_non_oos_advanced_share = frac_to_json(
            selected["non_oos_advanced_share"]
        )
    return {
        "model_version": model_version,
        "train_fingerprint": train_fingerprint,
        "validation_fingerprint": val_fingerprint,
        "counts": counts,
        "frozen_config_summary": {
            "config": FROZEN_CONFIG,
            "cv_best_params": FROZEN_CV_BEST_PARAMS,
        },
        "status": status,
        "raw_11_class": _raw_to_json(raw),
        "selected_theta": None if selected is None else selected["theta"],
        "highest_feasible_theta_in_declared_grid": highest_feasible_theta,
        "selected_non_oos_routed_to_advanced_share": (
            selected_non_oos_advanced_share
        ),
        "routed_at_theta": _routed_to_json(selected),
        "threshold_scan": {
            "candidates": [entry["theta"] for entry in scan],
            "entries": scan_json,
            "feasible_count": feasible_count,
            "best_achievable_oos_tp": best_achievable_oos_tp,
        },
        "exploratory_baselines": exploratory_baselines,
        "determinism_evidence": determinism_evidence,
    }


def dumps_json_report(report: dict[str, Any]) -> bytes:
    """Serialize report as UTF-8 JSON bytes with LF ending."""
    text = json.dumps(
        report,
        sort_keys=True,
        indent=2,
        ensure_ascii=False,
        default=_json_default,
    )
    return (text + "\n").encode("utf-8")


def write_bytes(path: str | Path, data: bytes) -> None:
    """Write bytes to a file, creating parent directories if needed."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def _fmt_frac(value: Fraction) -> str:
    return f"{float(value):.6f}"


def _render_matrix(labels: Sequence[str], matrix: list[list[int]]) -> list[str]:
    header = "| actual \\ predicted | " + " | ".join(labels) + " |"
    sep = "| --- | " + " | ".join(["---:"] * len(labels)) + " |"
    lines = [header, sep]
    for label, row in zip(labels, matrix):
        lines.append(
            "| " + label + " | " + " | ".join(str(v) for v in row) + " |"
        )
    return lines


def build_markdown_report(
    *,
    model_version: str,
    train_fingerprint: str,
    val_fingerprint: str,
    counts: dict[str, int],
    status: str,
    raw: dict[str, Any],
    selected: dict[str, Any] | None,
    scan: list[dict[str, Any]],
    feasible_count: int,
    best_achievable_oos_tp: int,
    exploratory_baselines: dict[str, Any],
    highest_feasible_theta: float | None = None,
    determinism_evidence: str | None = None,
) -> str:
    """Build the Markdown validation report."""
    lines: list[str] = []
    lines.append("# Chat Classifier B2 Model K Validation Evaluation")
    lines.append("")

    lines.append("## 1. Summary")
    lines.append(f"- Status: **{status}**")
    lines.append(f"- Model version: `{model_version}`")
    if selected is None:
        lines.append("- Selected theta: `NONE`")
    else:
        lines.append(f"- Selected theta: `{repr(float(selected['theta']))}`")
        lines.append(
            "- Local macro-F1 (routed): "
            f"`{_fmt_frac(selected['local_macro_f1'])}`"
        )
        lines.append(
            f"- OOS recall (routed): `{_fmt_frac(selected['oos_recall'])}`"
        )
        lines.append(
            f"- OOS precision (routed): `{_fmt_frac(selected['oos_precision'])}`"
        )
        lines.append(
            f"- False-local rate: `{_fmt_frac(selected['false_local_rate'])}`"
        )
        lines.append(
            "- Correct local coverage: "
            f"`{_fmt_frac(selected['correct_local_coverage'])}`"
        )
    if highest_feasible_theta is None:
        lines.append("- Highest feasible theta in declared grid: `NONE`")
    else:
        lines.append(
            "- Highest feasible theta in declared grid: "
            f"`{repr(float(highest_feasible_theta))}`"
        )
    if selected is not None:
        lines.append(
            "- Share of non-OOS validation routed to ADVANCED (LLM) at selected theta: "
            f"`{_fmt_frac(selected['non_oos_advanced_share'])}` "
            f"(`{selected['non_oos_routed_advanced']} / {selected['non_oos_count']}`)"
        )
    lines.append(f"- Raw 11-class accuracy: `{_fmt_frac(raw['accuracy'])}`")
    lines.append(f"- Raw 11-class macro-F1: `{_fmt_frac(raw['macro_f1'])}`")
    lines.append(f"- Train fingerprint: `{train_fingerprint}`")
    lines.append(f"- Validation fingerprint: `{val_fingerprint}`")
    lines.append(
        "- Counts: "
        f"train={counts['train']}, val={counts['val']}, "
        f"local={counts['local']}, "
        f"advanced={counts['advanced_conversation']}, oos={counts['oos']}"
    )
    if determinism_evidence:
        lines.append(f"- Determinism evidence: {determinism_evidence}")
    lines.append("")

    lines.append("## 2. Validation-selection caveat")
    lines.append(
        "Theta was selected on the validation set (`val_accept_b2.jsonl`). "
        "All validation metrics at the selected theta are in-sample and are "
        "NOT a generalization estimate. `val_accept_b2` is a validation set "
        "(used for model comparison and theta selection), NOT an independent "
        "blind test."
    )
    lines.append("")

    lines.append("## 3. Conventions")
    lines.append(
        "- Zero-denominator rule: any ratio with denominator 0 is reported "
        "as 0 (0/1)."
    )
    lines.append(
        "- Confidence threshold is inclusive: a prediction passes when "
        "`confidence >= theta`; `confidence < theta` falls back to "
        "`ADVANCED` (unless intent is `ADVANCED_CONVERSATION`, which "
        "routes `ADVANCED` first)."
    )
    lines.append(
        "- Selection order among feasible candidates: maximize "
        "`local_macro_f1`, minimize `false_local_count`, maximize "
        "`correct_local_count`, maximize `theta` (exact comparison, no "
        "tolerance)."
    )
    lines.append(
        "- Feasibility rule: `oos_feasible = (oos_tp * 10 >= 9 * N_oos)` "
        "(integer arithmetic)."
    )
    lines.append(
        "- Over-refusal rate (non-OOS routed to REFUSED) is a reporting "
        "guard only, not a gate."
    )
    lines.append("")

    lines.append("## 4. Raw 11-class metrics")
    lines.append(f"- Accuracy: `{_fmt_frac(raw['accuracy'])}`")
    lines.append(f"- Macro-F1: `{_fmt_frac(raw['macro_f1'])}`")
    lines.append("")
    lines.append("| Intent | Precision | Recall | F1 | Support |")
    lines.append("| --- | ---: | ---: | ---: | ---: |")
    for intent in INTENTS:
        stats = raw["per_class"][intent]
        lines.append(
            f"| {intent} | {_fmt_frac(stats['precision'])} | "
            f"{_fmt_frac(stats['recall'])} | {_fmt_frac(stats['f1'])} | "
            f"{stats['support']} |"
        )
    lines.append("")
    lines.append(
        "Confusion matrix (rows = actual, columns = predicted, "
        "taxonomy order):"
    )
    lines.append("")
    labels = raw["confusion_matrix"]["labels"]
    lines.extend(_render_matrix(labels, raw["confusion_matrix"]["matrix"]))
    lines.append("")

    lines.append("## 5. Routed local metrics at theta")
    if selected is None:
        lines.append(
            "No feasible theta selected; routed local metrics at theta are "
            "not available."
        )
    else:
        lines.append(f"- Selected theta: `{repr(float(selected['theta']))}`")
        lines.append(
            f"- Local macro-F1: `{_fmt_frac(selected['local_macro_f1'])}`"
        )
        lines.append("")
        lines.append("| Local intent | Precision | Recall | F1 | Support |")
        lines.append("| --- | ---: | ---: | ---: | ---: |")
        for intent in LOCAL_INTENTS:
            stats = selected["per_class_local"][intent]
            lines.append(
                f"| {intent} | {_fmt_frac(stats['precision'])} | "
                f"{_fmt_frac(stats['recall'])} | {_fmt_frac(stats['f1'])} | "
                f"{stats['support']} |"
            )
    lines.append("")

    lines.append("## 6. OOS and false-local")
    if selected is None:
        lines.append("- No feasible theta selected.")
        lines.append(
            "- Best achievable OOS true positives: "
            f"`{best_achievable_oos_tp}`"
        )
    else:
        lines.append(
            f"- `oos_tp / N_oos`: `{selected['oos_tp']} / {selected['n_oos']}`"
        )
        lines.append(f"- OOS recall: `{_fmt_frac(selected['oos_recall'])}`")
        lines.append(f"- OOS precision: `{_fmt_frac(selected['oos_precision'])}`")
        lines.append(
            "- Over-refusal (non-OOS routed REFUSED): "
            f"`{selected['over_refusal_count']}` / `{selected['non_oos_count']}` "
            f"(`{_fmt_frac(selected['over_refusal_rate'])}`, 5% guard only)"
        )
        lines.append(
            f"- OOS -> ADVANCED: `{selected['oos_to_advanced']}`"
        )
        lines.append(
            f"- OOS -> local-intent (LOCAL): `{selected['oos_to_local_intent']}`"
        )
        lines.append(
            "- `false_local_count / N_nonlocal`: "
            f"`{selected['false_local_count']} / {selected['n_nonlocal']}`"
        )
        lines.append(
            f"- False-local rate: `{_fmt_frac(selected['false_local_rate'])}`"
        )
    lines.append("")

    lines.append("## 7. Local coverage")
    if selected is None:
        lines.append(
            "No feasible theta selected; coverage metrics at theta are not "
            "available."
        )
    else:
        lines.append(
            "- `local_route_coverage`: "
            f"`{_fmt_frac(selected['local_route_coverage'])}`"
        )
        lines.append(
            "- `correct_local_coverage`: "
            f"`{_fmt_frac(selected['correct_local_coverage'])}`"
        )
        lines.append(
            "- `local_route_precision`: "
            f"`{_fmt_frac(selected['local_route_precision'])}`"
        )
        rc = selected["route_counts"]
        lines.append(
            "- Route counts: "
            f"LOCAL={rc['LOCAL']}, ADVANCED={rc['ADVANCED']}, "
            f"REFUSED={rc['REFUSED']}"
        )
        lines.append(
            "- Low-confidence fallback count: "
            f"`{selected['low_confidence_fallback_count']}`"
        )
    lines.append("")

    lines.append("## 8. Threshold scan summary")
    lines.append(f"- Candidate count: `{len(scan)}`")
    lines.append(f"- Feasible count: `{feasible_count}`")
    if highest_feasible_theta is None:
        lines.append("- Highest feasible theta in declared grid: `NONE`")
    else:
        lines.append(
            "- Highest feasible theta in declared grid: "
            f"`{repr(float(highest_feasible_theta))}`"
        )
    lines.append(
        "- Best achievable OOS true positives: "
        f"`{best_achievable_oos_tp}`"
    )
    feasible_entries = [entry for entry in scan if entry["feasible"]]
    feasible_entries.sort(key=selection_key, reverse=True)
    lines.append("")
    lines.append("First 10 feasible candidates in selection ranking:")
    lines.append("")
    lines.append(
        "| Rank | Theta | Local macro-F1 | False-local count | "
        "Correct local count | OOS TP |"
    )
    lines.append("| ---: | ---: | ---: | ---: | ---: | ---: |")
    if feasible_entries:
        for rank, entry in enumerate(feasible_entries[:10], start=1):
            lines.append(
                f"| {rank} | {repr(float(entry['theta']))} | "
                f"{_fmt_frac(entry['local_macro_f1'])} | "
                f"{entry['false_local_count']} | "
                f"{entry['correct_local_count']} | {entry['oos_tp']} |"
            )
    else:
        lines.append("| - | NONE | - | - | - | - |")
    lines.append("")

    lines.append("## 9. Exploratory baselines")
    lines.append(
        "Exploratory baselines are reported as raw 11-class accuracy and "
        "macro-F1 only. Baselines are exploratory and never select theta."
    )
    lines.append(
        "The keyword baseline keyword list was authored after the "
        "validation set was inspected, so its numbers are optimistic and "
        "not a fair comparator. Its rule (zero hits -> `OUT_OF_SCOPE`, "
        "more than one intent matched -> `ADVANCED_CONVERSATION`) is a "
        "coarse proxy for 'needs more than one local source' and is not "
        "the label definition."
    )
    lines.append(
        "The length baseline is fitted on train only and is diagnostic."
    )
    lines.append("")
    lines.append("| Baseline | Raw accuracy | Raw macro-F1 |")
    lines.append("| --- | ---: | ---: |")
    for name, stats in exploratory_baselines.items():
        lines.append(
            f"| {name} | {_fmt_frac(stats['raw_accuracy'])} | "
            f"{_fmt_frac(stats['raw_macro_f1'])} |"
        )
    lines.append("")

    if status == "FAILURE":
        lines.append("## 10. Failure")
        n_oos = counts.get("oos", 0)
        best_recall = frac(best_achievable_oos_tp, n_oos)
        lines.append(
            "- Best achievable OOS recall over all candidates: "
            f"`{_fmt_frac(best_recall)}` "
            f"(`{best_achievable_oos_tp} / {n_oos}`)."
        )
        lines.append(
            "- The 0.90 OOS target was not relaxed and must be escalated "
            "to the PM."
        )
        lines.append("")

    return "\n".join(lines) + "\n"


def write_text(path: str | Path, text: str) -> None:
    """Write UTF-8 text with LF endings as bytes."""
    write_bytes(path, text.encode("utf-8"))


def write_reports(
    json_path: str | Path,
    md_path: str | Path,
    report: dict[str, Any],
    markdown: str,
) -> None:
    """Write JSON and Markdown report files."""
    write_bytes(json_path, dumps_json_report(report))
    write_text(md_path, markdown)
