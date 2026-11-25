"""Build and verify the frozen Model K chat intent classifier artifact."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

from app.chat_training import (
    DEFAULT_CONFIG,
    DatasetError,
    ModelLoadError,
    dataset_fingerprint,
    load_dataset,
    load_model,
    save_artifacts,
    train,
)

FROZEN_MODEL_VERSION = "chat-modelk-lr-d98a37b805f0f770"
FROZEN_TRAIN_FINGERPRINT = (
    "651e5e5fa095c712ed77d8794b810d52fd0cab63cbd5e3ebba0abea466f7b185"
)
FROZEN_VAL_FINGERPRINT = (
    "337870a23c44ed6d58f97246d794fc6b21aa87eaf787f27f85e66d97b0e6eee8"
)
FROZEN_TRAIN_COUNT = 887


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m scripts.build_chat_model",
        description="Build frozen Model K chat classifier from train_v2.jsonl.",
    )
    parser.add_argument(
        "--train",
        default="data/chat/train_v2.jsonl",
        help="Path to canonical training JSONL",
    )
    parser.add_argument(
        "--out-dir",
        default="models/chat_modelk_spec",
        help="Output directory for model artifacts",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing artifacts in out-dir",
    )

    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return exc.code if isinstance(exc.code, int) else 2

    out_dir = Path(args.out_dir)
    joblib_path = out_dir / "chat_classifier.joblib"
    if joblib_path.exists() and not args.force:
        print(
            f"ERROR: {joblib_path} already exists; pass --force to overwrite",
            file=sys.stderr,
        )
        return 1

    try:
        records = load_dataset(args.train)
    except (DatasetError, OSError, UnicodeDecodeError) as exc:
        print(f"ERROR: failed to load dataset: {exc}", file=sys.stderr)
        return 1

    if len(records) != FROZEN_TRAIN_COUNT:
        print(
            f"ERROR: expected {FROZEN_TRAIN_COUNT} records, got {len(records)}",
            file=sys.stderr,
        )
        return 1

    train_fp = dataset_fingerprint(records)
    if train_fp != FROZEN_TRAIN_FINGERPRINT:
        print(
            "ERROR: train_fingerprint mismatch: "
            f"expected {FROZEN_TRAIN_FINGERPRINT}, got {train_fp}",
            file=sys.stderr,
        )
        return 1

    result = train(records, DEFAULT_CONFIG)
    save_artifacts(result, FROZEN_VAL_FINGERPRINT, out_dir)

    try:
        loaded = load_model(out_dir)
    except ModelLoadError as exc:
        print(f"ERROR: failed to reload built model: {exc}", file=sys.stderr)
        return 1

    if loaded.model_version != FROZEN_MODEL_VERSION:
        print(
            "ERROR: model_version mismatch: "
            f"expected {FROZEN_MODEL_VERSION}, got {loaded.model_version}",
            file=sys.stderr,
        )
        return 1

    meta_fp = loaded.meta.get("train_fingerprint")
    if meta_fp != FROZEN_TRAIN_FINGERPRINT:
        print(
            "ERROR: meta train_fingerprint mismatch: "
            f"expected {FROZEN_TRAIN_FINGERPRINT}, got {meta_fp}",
            file=sys.stderr,
        )
        return 1

    print(f"MODEL_VERSION={loaded.model_version}")
    print(f"TRAIN_FINGERPRINT={meta_fp}")
    print(f"TRAIN_COUNT={len(records)}")
    print(f"OUT_DIR={out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
