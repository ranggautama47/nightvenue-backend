"""Evaluate the chat intent classifier on the validation set and select a threshold."""

from __future__ import annotations

import argparse
import sys
from typing import Any, Sequence

from app.chat_baselines import KeywordBaseline, LengthBaseline
from app.chat_evaluation import (
    FROZEN_CONFIG,
    FROZEN_CV_BEST_PARAMS,
    IdentityError,
    build_json_report,
    build_markdown_report,
    evaluate_raw,
    select_threshold,
    verify_identity,
    write_reports,
)
from app.chat_text import INTENTS, LOCAL_INTENTS
from app.chat_training import (
    PROBE_TEXTS,
    DatasetError,
    ModelLoadError,
    load_dataset,
    load_model,
    predict_batch,
)

FROZEN_MODEL_VERSION = "chat-charwb-lr-0a01d14c6704ab37"
FROZEN_TRAIN_FINGERPRINT = (
    "b049cffce6283ef118ef9f5f6ad6568e550995ee41cce37eb4fdf8b3e4481a37"
)
FROZEN_VAL_FINGERPRINT = (
    "3af03a264b6c85b9755a76e4445c343c3b4583f78e63eed2ac0c75ff2727eb8a"
)
FROZEN_TRAIN_COUNT = 576
FROZEN_VAL_COUNT = 288
FROZEN_OOS_COUNT = 48
FROZEN_ADVANCED_COUNT = 24
FROZEN_LOCAL_COUNT = 216
FROZEN_PROBE_EXPECTED = (
    ("OPENING_HOURS", "0.786016"),
    ("GENERAL_VENUE_INFORMATION", "0.704875"),
    ("PROMOTION", "0.888151"),
    ("BOTTLE_KEEPING", "0.985561"),
    ("OUT_OF_SCOPE", "0.923350"),
    ("OPENING_HOURS", "0.801434"),
)


def _baseline_raw(
    train_records: list[dict], val_records: list[dict]
) -> dict[str, Any]:
    true_intents = [record["intent"] for record in val_records]
    texts = [record["text"] for record in val_records]

    keyword = KeywordBaseline()
    keyword_preds = [
        {"intent": keyword.predict(text), "confidence": 1.0} for text in texts
    ]
    keyword_raw = evaluate_raw(true_intents, keyword_preds)

    length = LengthBaseline().fit(train_records)
    length_preds = [
        {"intent": length.predict(text), "confidence": 1.0} for text in texts
    ]
    length_raw = evaluate_raw(true_intents, length_preds)

    return {
        "keyword": {
            "raw_accuracy": keyword_raw["accuracy"],
            "raw_macro_f1": keyword_raw["macro_f1"],
        },
        "length": {
            "raw_accuracy": length_raw["accuracy"],
            "raw_macro_f1": length_raw["macro_f1"],
        },
    }


def _counts(train_records: list[dict], val_records: list[dict]) -> dict[str, int]:
    local_set = set(LOCAL_INTENTS)
    return {
        "train": len(train_records),
        "val": len(val_records),
        "local": sum(1 for r in val_records if r.get("intent") in local_set),
        "advanced_conversation": sum(
            1
            for r in val_records
            if r.get("intent") == "ADVANCED_CONVERSATION"
        ),
        "oos": sum(1 for r in val_records if r.get("intent") == "OUT_OF_SCOPE"),
    }


def _print_stdout(
    model_version: str,
    selected: dict[str, Any] | None,
    raw: dict[str, Any],
) -> None:
    feasible = selected is not None
    if feasible:
        theta_selected = repr(float(selected["theta"]))
        local_macro_f1 = f"{float(selected['local_macro_f1']):.6f}"
        oos_recall = f"{float(selected['oos_recall']):.6f}"
        false_local_count = str(selected["false_local_count"])
        false_local_rate = f"{float(selected['false_local_rate']):.6f}"
        local_route_coverage = (
            f"{float(selected['local_route_coverage']):.6f}"
        )
        correct_local_coverage = (
            f"{float(selected['correct_local_coverage']):.6f}"
        )
        local_route_precision = (
            f"{float(selected['local_route_precision']):.6f}"
        )
    else:
        theta_selected = "NONE"
        local_macro_f1 = "NONE"
        oos_recall = "NONE"
        false_local_count = "NONE"
        false_local_rate = "NONE"
        local_route_coverage = "NONE"
        correct_local_coverage = "NONE"
        local_route_precision = "NONE"

    print(f"MODEL_VERSION={model_version}")
    print(f"THETA_SELECTED={theta_selected}")
    print(f"LOCAL_MACRO_F1={local_macro_f1}")
    print(f"OOS_RECALL={oos_recall}")
    print(f"FALSE_LOCAL_COUNT={false_local_count}")
    print(f"FALSE_LOCAL_RATE={false_local_rate}")
    print(f"LOCAL_ROUTE_COVERAGE={local_route_coverage}")
    print(f"CORRECT_LOCAL_COVERAGE={correct_local_coverage}")
    print(f"LOCAL_ROUTE_PRECISION={local_route_precision}")
    print(f"FEASIBLE={'true' if feasible else 'false'}")
    print(f"RAW_ACCURACY={float(raw['accuracy']):.6f}")
    print(f"RAW_MACRO_F1={float(raw['macro_f1']):.6f}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m scripts.evaluate_chat_classifier",
        description=(
            "Evaluate chat intent classifier and select validation threshold."
        ),
    )
    parser.add_argument(
        "--model-dir", default="models", help="Model artifact directory"
    )
    parser.add_argument(
        "--train", default="data/chat/train.jsonl", help="Train JSONL path"
    )
    parser.add_argument(
        "--val", default="data/chat/val.jsonl", help="Validation JSONL path"
    )
    parser.add_argument(
        "--out-md",
        default="reports/chat_classifier_b2_validation.md",
        help="Output Markdown report path",
    )
    parser.add_argument(
        "--out-json",
        default="reports/chat_classifier_b2_validation_metrics.json",
        help="Output JSON metrics path",
    )

    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return exc.code if isinstance(exc.code, int) else 2

    try:
        train_records = load_dataset(args.train)
        val_records = load_dataset(args.val)
        model = load_model(args.model_dir)

        class_labels = model.meta.get("class_order")
        if not isinstance(class_labels, list):
            raise IdentityError(
                "Identity mismatch for class_labels: meta.class_order missing"
            )

        pipeline_classes = getattr(model.pipeline, "classes_", None)
        if pipeline_classes is not None:
            pipeline_labels = [str(name) for name in list(pipeline_classes)]
            if sorted(pipeline_labels) != sorted([str(x) for x in class_labels]):
                raise IdentityError(
                    "Identity mismatch for pipeline classes: "
                    f"expected sorted {sorted([str(x) for x in class_labels])!r}, "
                    f"got sorted {sorted(pipeline_labels)!r}"
                )

        probe_predictions = predict_batch(model, list(PROBE_TEXTS))
        verify_identity(
            meta=model.meta,
            model_version=model.model_version,
            class_labels=class_labels,
            train_records=train_records,
            val_records=val_records,
            probe_predictions=probe_predictions,
            expected_model_version=FROZEN_MODEL_VERSION,
            expected_train_fingerprint=FROZEN_TRAIN_FINGERPRINT,
            expected_val_fingerprint=FROZEN_VAL_FINGERPRINT,
            expected_train_count=FROZEN_TRAIN_COUNT,
            expected_val_count=FROZEN_VAL_COUNT,
            expected_oos_count=FROZEN_OOS_COUNT,
            expected_advanced_count=FROZEN_ADVANCED_COUNT,
            expected_local_count=FROZEN_LOCAL_COUNT,
            expected_intents=list(INTENTS),
            expected_config=FROZEN_CONFIG,
            expected_cv_best_params=FROZEN_CV_BEST_PARAMS,
            expected_probe=FROZEN_PROBE_EXPECTED,
        )

        texts = [record["text"] for record in val_records]
        true_intents = [record["intent"] for record in val_records]
        predictions = predict_batch(model, texts)

        raw = evaluate_raw(true_intents, predictions)
        selection = select_threshold(true_intents, predictions)
        baselines = _baseline_raw(train_records, val_records)
        counts = _counts(train_records, val_records)
        status = "SUCCESS" if selection["selected"] is not None else "FAILURE"

        report = build_json_report(
            model_version=model.model_version,
            train_fingerprint=FROZEN_TRAIN_FINGERPRINT,
            val_fingerprint=FROZEN_VAL_FINGERPRINT,
            counts=counts,
            status=status,
            raw=raw,
            selected=selection["selected"],
            scan=selection["scan"],
            feasible_count=selection["feasible_count"],
            best_achievable_oos_tp=selection["best_achievable_oos_tp"],
            exploratory_baselines=baselines,
        )
        markdown = build_markdown_report(
            model_version=model.model_version,
            train_fingerprint=FROZEN_TRAIN_FINGERPRINT,
            val_fingerprint=FROZEN_VAL_FINGERPRINT,
            counts=counts,
            status=status,
            raw=raw,
            selected=selection["selected"],
            scan=selection["scan"],
            feasible_count=selection["feasible_count"],
            best_achievable_oos_tp=selection["best_achievable_oos_tp"],
            exploratory_baselines=baselines,
        )
        write_reports(args.out_json, args.out_md, report, markdown)
        _print_stdout(model.model_version, selection["selected"], raw)
        return 0 if selection["selected"] is not None else 1
    except (
        ModelLoadError,
        DatasetError,
        IdentityError,
        OSError,
        ValueError,
        KeyError,
        TypeError,
    ) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
