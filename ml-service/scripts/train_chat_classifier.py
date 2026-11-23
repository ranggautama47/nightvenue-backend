"""Train chat intent classifier and save artifacts."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

from app.chat_training import (
    DEFAULT_CONFIG,
    PROBE_TEXTS,
    dataset_fingerprint,
    load_dataset,
    load_model,
    predict_batch,
    save_artifacts,
    train,
)
from scripts.check_chat_leakage import find_near_duplicates

ML_SERVICE_DIR = Path(__file__).resolve().parent.parent


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m scripts.train_chat_classifier",
        description="Train chat intent classifier and save artifacts.",
    )
    parser.add_argument(
        "--train",
        default=str(ML_SERVICE_DIR / "data" / "chat" / "train.jsonl"),
        help="Path to train JSONL file",
    )
    parser.add_argument(
        "--val",
        default=str(ML_SERVICE_DIR / "data" / "chat" / "val.jsonl"),
        help="Path to validation JSONL file",
    )
    parser.add_argument(
        "--out-dir",
        default=str(ML_SERVICE_DIR / "models"),
        help="Output directory for model artifacts",
    )

    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return exc.code if isinstance(exc.code, int) else 2

    train_path = Path(args.train)
    val_path = Path(args.val)
    out_dir = Path(args.out_dir)

    # Load datasets
    try:
        train_records = load_dataset(train_path)
        val_records = load_dataset(val_path)
    except Exception as e:
        print(str(e), file=sys.stderr)
        return 2

    # Check for near-duplicates
    near_dup_pairs = find_near_duplicates(train_records, val_records)
    if near_dup_pairs:
        for train_id, val_id, score in near_dup_pairs:
            print(f"NEAR_DUPLICATE train={train_id} val={val_id} cosine={score:.4f}")
        print("training refused: near-duplicate leakage")
        return 1

    # Train
    try:
        result = train(train_records, DEFAULT_CONFIG)
    except Exception as e:
        print(str(e), file=sys.stderr)
        return 2

    # Save artifacts
    val_fp = dataset_fingerprint(val_records)
    save_artifacts(result, val_fp, out_dir)

    # Load model for probe predictions
    loaded = load_model(out_dir)
    probe_preds = predict_batch(loaded, PROBE_TEXTS)

    # Output
    print(f"MODEL_VERSION={result.model_version}")
    print(f"TRAIN_FINGERPRINT={result.train_fingerprint}")
    print(f"VAL_FINGERPRINT={val_fp}")
    print(f"CV_BEST_C={result.cv_best_params.get('clf__C', 'N/A')}")
    print(f"CV_BEST_F1_MACRO={result.cv_best_score:.6f}")
    for i, (text, pred) in enumerate(zip(PROBE_TEXTS, probe_preds), 1):
        print(f"PROBE {i} {pred.intent} {pred.confidence:.6f}")
    print(
        "NOTE: validation data was used only for schema and leakage checks, "
        "never for fitting or tuning; CV score is on train only and is not a generalization claim."
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
