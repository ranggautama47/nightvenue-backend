"""Tests for the near-duplicate leakage check."""

from __future__ import annotations

import inspect
import json
from pathlib import Path

import scripts.check_chat_leakage as leakage
from app.chat_training import build_char_vectorizer
from scripts.check_chat_leakage import (
    NOTE_LINE,
    find_near_duplicates,
    is_near_duplicate,
    main,
)


def _write_jsonl(path: Path, records: list[dict | str]) -> None:
    lines: list[str] = []
    for record in records:
        if isinstance(record, str):
            lines.append(record)
        else:
            lines.append(json.dumps(record, ensure_ascii=False))
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def _record(
    record_id: str,
    text: str,
    intent: str = "OPENING_HOURS",
    lang: str = "en",
    source: str = "synthetic",
) -> dict:
    return {
        "id": record_id,
        "text": text,
        "intent": intent,
        "lang": lang,
        "source": source,
    }


TRAIN_TEXT = "berapa minimum spend untuk meja vip malam ini"
VAL_TEXT = "berapa minimum spend untuk meja vip malam ini ya"


class _RecordingVectorizer:
    """Wraps the real vectorizer and records what fit_transform received."""

    def __init__(self) -> None:
        self.inner = build_char_vectorizer()
        self.fitted_texts: list[str] = []

    def fit_transform(self, texts):
        self.fitted_texts = list(texts)
        return self.inner.fit_transform(texts)

    def transform(self, texts):
        return self.inner.transform(texts)


class TestIsNearDuplicate:
    def test_boundary_false(self) -> None:
        assert is_near_duplicate(0.0) is False
        assert is_near_duplicate(0.849) is False
        assert is_near_duplicate(0.8499) is False

    def test_boundary_true(self) -> None:
        assert is_near_duplicate(0.85) is True
        assert is_near_duplicate(0.851) is True
        assert is_near_duplicate(0.9) is True
        assert is_near_duplicate(1.0) is True

    def test_custom_threshold(self) -> None:
        assert is_near_duplicate(0.9, threshold=0.95) is False
        assert is_near_duplicate(0.95, threshold=0.95) is True


class TestFindNearDuplicates:
    def test_positive_pair_found(self) -> None:
        train_records = [_record("tr-0001", TRAIN_TEXT, "MINIMUM_SPEND", "id")]
        val_records = [_record("va-0001", VAL_TEXT, "MINIMUM_SPEND", "id")]

        pairs = find_near_duplicates(train_records, val_records)

        assert len(pairs) == 1
        train_id, val_id, score = pairs[0]
        assert (train_id, val_id) == ("tr-0001", "va-0001")
        assert score >= 0.85

    def test_negative_pair_not_found(self) -> None:
        train_records = [_record("tr-0001", "jam buka hari ini", "OPENING_HOURS", "id")]
        val_records = [
            _record(
                "va-0001",
                "tolong ceritakan sejarah musik elektronik dunia",
                "OUT_OF_SCOPE",
                "id",
            )
        ]

        assert find_near_duplicates(train_records, val_records) == []

    def test_cross_split_only(self) -> None:
        # A duplicate wholly inside train is not a train/val leakage pair.
        train_records = [
            _record("tr-0001", "meja dekat panggung ada nggak", "VENUE_AREA", "id"),
            _record("tr-0002", "meja dekat panggung ada nggak", "VENUE_AREA", "id"),
        ]
        val_records = [
            _record(
                "va-0001",
                "tolong jelaskan cara kerja fotosintesis",
                "OUT_OF_SCOPE",
                "id",
            )
        ]

        assert find_near_duplicates(train_records, val_records) == []

    def test_threshold_boundary_is_inclusive(self) -> None:
        train_records = [_record("tr-0001", TRAIN_TEXT, "MINIMUM_SPEND", "id")]
        val_records = [_record("va-0001", VAL_TEXT, "MINIMUM_SPEND", "id")]

        pairs = find_near_duplicates(train_records, val_records)
        assert len(pairs) == 1
        score = pairs[0][2]

        # A threshold set exactly at the measured score still detects the pair.
        assert len(find_near_duplicates(train_records, val_records, threshold=score)) == 1
        assert find_near_duplicates(train_records, val_records, threshold=score + 0.001) == []

    def test_labels_do_not_affect_detection(self) -> None:
        train_records = [_record("tr-0001", TRAIN_TEXT, "MINIMUM_SPEND", "id")]
        same_label = [_record("va-0001", VAL_TEXT, "MINIMUM_SPEND", "id")]
        other_label = [_record("va-0001", VAL_TEXT, "OUT_OF_SCOPE", "id")]

        assert find_near_duplicates(train_records, same_label) == find_near_duplicates(
            train_records, other_label
        )

    def test_determinism(self) -> None:
        train_records = [_record("tr-0001", TRAIN_TEXT, "MINIMUM_SPEND", "id")]
        val_records = [_record("va-0001", VAL_TEXT, "MINIMUM_SPEND", "id")]

        assert find_near_duplicates(
            train_records, val_records
        ) == find_near_duplicates(train_records, val_records)

    def test_vectorizer_is_fitted_on_combined_texts(self, monkeypatch) -> None:
        spy = _RecordingVectorizer()
        monkeypatch.setattr(leakage, "_get_vectorizer", lambda: spy)

        train_records = [_record("tr-0001", "jam buka hari ini", "OPENING_HOURS", "id")]
        val_records = [_record("va-0001", "fotosintesis tumbuhan hijau", "OUT_OF_SCOPE", "id")]

        find_near_duplicates(train_records, val_records)

        assert spy.fitted_texts == [
            "jam buka hari ini",
            "fotosintesis tumbuhan hijau",
        ]
        # Vocabulary must contain n-grams that only the validation text produced.
        vocabulary = spy.inner.vocabulary_
        assert any("buk" in term for term in vocabulary)
        assert any("fot" in term for term in vocabulary)

    def test_pairs_are_sorted_deterministically(self) -> None:
        train_records = [
            _record("tr-0001", TRAIN_TEXT, "MINIMUM_SPEND", "id"),
            _record(
                "tr-0002",
                "is there a dress code for guests",
                "GENERAL_VENUE_INFORMATION",
                "en",
            ),
        ]
        val_records = [
            _record(
                "va-0002",
                "is there a dress code for guests here",
                "GENERAL_VENUE_INFORMATION",
                "en",
            ),
            _record("va-0001", VAL_TEXT, "MINIMUM_SPEND", "id"),
        ]

        pairs = find_near_duplicates(train_records, val_records)

        assert len(pairs) == 2
        assert pairs == sorted(pairs, key=lambda pair: (-pair[2], pair[0], pair[1]))

    def test_signature(self) -> None:
        sig = inspect.signature(find_near_duplicates)
        assert list(sig.parameters) == ["train_records", "val_records", "threshold"]


class TestMainWithNearDup:
    def test_near_dup_flag_returns_one_and_prints(self, tmp_path: Path, capsys) -> None:
        train = tmp_path / "train.jsonl"
        val = tmp_path / "val.jsonl"
        _write_jsonl(train, [_record("tr-0001", TRAIN_TEXT, "MINIMUM_SPEND", "id")])
        _write_jsonl(val, [_record("va-0001", VAL_TEXT, "MINIMUM_SPEND", "id")])

        code = main(["--train", str(train), "--val", str(val), "--near-dup"])
        captured = capsys.readouterr()

        assert code == 1
        assert "NEAR_DUPLICATE train=tr-0001 val=va-0001 cosine=" in captured.out
        assert "near-duplicate pairs: 1" in captured.out
        assert "threshold 0.85" in captured.out
        assert "TF-IDF char_wb 2-4" in captured.out
        assert "train vs val" in captured.out
        # Never prints record text
        assert "berapa minimum spend" not in captured.out
        # NOTE_LINE not printed when --near-dup
        assert NOTE_LINE not in captured.out

    def test_without_near_dup_flag_round_a_behavior(self, tmp_path: Path, capsys) -> None:
        train = tmp_path / "train.jsonl"
        val = tmp_path / "val.jsonl"
        _write_jsonl(train, [_record("tr-0001", "jam buka hari ini", "OPENING_HOURS", "id")])
        _write_jsonl(val, [_record("va-0001", "jam buka besok berapa", "OPENING_HOURS", "id")])

        code = main(["--train", str(train), "--val", str(val)])
        captured = capsys.readouterr()

        assert code == 0
        assert NOTE_LINE in captured.out
        assert "near-duplicate pairs" not in captured.out
        assert "train total: 1" in captured.out
        assert "val total: 1" in captured.out

    def test_near_dup_flag_with_no_duplicates_returns_zero(self, tmp_path: Path, capsys) -> None:
        train = tmp_path / "train.jsonl"
        val = tmp_path / "val.jsonl"
        _write_jsonl(train, [_record("tr-0001", "jam buka hari ini", "OPENING_HOURS", "id")])
        _write_jsonl(
            val,
            [_record("va-0001", "tolong ceritakan sejarah musik", "OUT_OF_SCOPE", "id")],
        )

        code = main(["--train", str(train), "--val", str(val), "--near-dup"])
        captured = capsys.readouterr()

        assert code == 0
        assert "near-duplicate pairs: 0" in captured.out
        assert NOTE_LINE not in captured.out

    def test_heldout_records_are_not_compared(self, tmp_path: Path, capsys) -> None:
        train = tmp_path / "train.jsonl"
        val = tmp_path / "val.jsonl"
        heldout = tmp_path / "heldout.jsonl"
        _write_jsonl(train, [_record("tr-0001", "jam buka hari ini", "OPENING_HOURS", "id")])
        _write_jsonl(
            val,
            [_record("va-0001", "tolong ceritakan sejarah musik", "OUT_OF_SCOPE", "id")],
        )
        # Near-identical to the train record: would be a pair if heldout were read.
        _write_jsonl(
            heldout,
            [_record("ho-0001", "jam buka hari ini ya", "OPENING_HOURS", "id")],
        )

        code = main(
            [
                "--train",
                str(train),
                "--val",
                str(val),
                "--heldout",
                str(heldout),
                "--near-dup",
            ]
        )
        captured = capsys.readouterr()

        assert code == 0
        assert "near-duplicate pairs: 0" in captured.out
        assert "ho-0001" not in captured.out

    def test_output_never_contains_record_text(self, tmp_path: Path, capsys) -> None:
        train = tmp_path / "train.jsonl"
        val = tmp_path / "val.jsonl"
        secret_text = "SECRET_TEXT_12345"
        _write_jsonl(train, [_record("tr-0001", secret_text, "OPENING_HOURS", "id")])
        _write_jsonl(val, [_record("va-0001", secret_text + " ya", "OPENING_HOURS", "id")])

        code = main(["--train", str(train), "--val", str(val), "--near-dup"])
        captured = capsys.readouterr()

        assert code == 1
        assert secret_text not in captured.out
        assert secret_text not in captured.err
