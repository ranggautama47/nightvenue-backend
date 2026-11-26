"""Tests for scripts/build_chat_model.py."""

from __future__ import annotations

from pathlib import Path

from scripts import build_chat_model, evaluate_chat_classifier


def test_frozen_constants_match_evaluator() -> None:
    assert (
        build_chat_model.FROZEN_MODEL_VERSION
        == evaluate_chat_classifier.FROZEN_MODEL_VERSION
    )
    assert (
        build_chat_model.FROZEN_TRAIN_FINGERPRINT
        == evaluate_chat_classifier.FROZEN_TRAIN_FINGERPRINT
    )
    assert (
        build_chat_model.FROZEN_VAL_FINGERPRINT
        == evaluate_chat_classifier.FROZEN_VAL_FINGERPRINT
    )
    assert (
        build_chat_model.FROZEN_TRAIN_COUNT
        == evaluate_chat_classifier.FROZEN_TRAIN_COUNT
    )


def test_build_chat_model_to_tmp_dir(tmp_path: Path, capsys) -> None:
    out_dir = tmp_path / "model_out"
    code = build_chat_model.main(["--out-dir", str(out_dir)])
    captured = capsys.readouterr()

    assert code == 0
    assert (out_dir / "chat_classifier.joblib").exists()
    assert (out_dir / "chat_classifier.meta.json").exists()
    assert f"MODEL_VERSION={build_chat_model.FROZEN_MODEL_VERSION}" in captured.out


def test_build_refuses_truncated_dataset(tmp_path: Path) -> None:
    src = Path("data/chat/train_v2.jsonl")
    lines = src.read_text(encoding="utf-8").splitlines()
    truncated = tmp_path / "train_truncated.jsonl"
    truncated.write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")

    out_dir = tmp_path / "model_out"
    code = build_chat_model.main(
        ["--train", str(truncated), "--out-dir", str(out_dir)]
    )
    assert code != 0
    assert not (out_dir / "chat_classifier.joblib").exists()


def test_build_refuses_overwrite_without_force(tmp_path: Path) -> None:
    out_dir = tmp_path / "model_out"
    assert build_chat_model.main(["--out-dir", str(out_dir)]) == 0
    assert build_chat_model.main(["--out-dir", str(out_dir)]) != 0
    assert build_chat_model.main(["--out-dir", str(out_dir), "--force"]) == 0
