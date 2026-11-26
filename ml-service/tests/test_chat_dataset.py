"""Repository-level checks for the chat intent train and validation datasets.

These tests read the real files under data/chat. TF-IDF cosine near-duplicate
detection is intentionally not covered here (assigned to a later round).
"""

import json
import re
from collections import Counter
from functools import lru_cache
from pathlib import Path

import pytest

from app.chat_text import INTENTS, MAX_MESSAGE_CODE_POINTS, normalize_for_dedup
from scripts.check_chat_leakage import VALID_LANGS, VALID_SOURCES, main

ML_SERVICE_DIR = Path(__file__).resolve().parent.parent
CHAT_DATA_DIR = ML_SERVICE_DIR / "data" / "chat"
SPLITS = ("train", "val")
SPLIT_PATHS = {
    "train": CHAT_DATA_DIR / "train.jsonl",
    "val": CHAT_DATA_DIR / "val.jsonl",
}
ID_PATTERNS = {
    "train": re.compile(r"tr-\d{4}"),
    "val": re.compile(r"va-\d{4}"),
}
REQUIRED_KEYS = {"id", "text", "intent", "lang", "source"}
VAL_MIN_LANG_PERCENT = {"id": 40, "en": 20, "mixed": 10}


def _load(path: Path) -> list[dict]:
    records: list[dict] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            stripped = line.strip()
            assert stripped, f"{path.name}:{line_no} is a blank line"
            payload = json.loads(stripped)
            assert isinstance(payload, dict), f"{path.name}:{line_no} is not a JSON object"
            records.append(payload)
    return records


@lru_cache(maxsize=None)
def _records(name: str) -> tuple[dict, ...]:
    return tuple(_load(SPLIT_PATHS[name]))


def _dedup_keys(name: str) -> list[str]:
    return [normalize_for_dedup(record["text"]) for record in _records(name)]


def _minimums(base: int, out_of_scope: int) -> dict[str, int]:
    minimums = {intent: base for intent in INTENTS}
    minimums["OUT_OF_SCOPE"] = out_of_scope
    return minimums


@pytest.mark.parametrize("name", SPLITS)
def test_split_file_has_no_bom_and_ends_with_newline(name: str) -> None:
    raw = SPLIT_PATHS[name].read_bytes()
    assert not raw.startswith(b"\xef\xbb\xbf")
    assert raw.endswith(b"\n")
    assert len(_records(name)) > 0


@pytest.mark.parametrize("name", SPLITS)
def test_records_have_exact_schema_and_valid_values(name: str) -> None:
    for position, record in enumerate(_records(name), start=1):
        where = f"{name} record {position}"
        assert set(record) == REQUIRED_KEYS, where
        assert all(isinstance(value, str) for value in record.values()), where
        assert record["id"].strip() == record["id"], where
        assert record["id"] != "", where
        assert record["intent"] in INTENTS, where
        assert record["lang"] in VALID_LANGS, where
        assert record["source"] in VALID_SOURCES, where


@pytest.mark.parametrize("name", SPLITS)
def test_text_is_non_empty_and_within_length_limit(name: str) -> None:
    for position, record in enumerate(_records(name), start=1):
        where = f"{name} record {position}"
        stripped = record["text"].strip()
        assert 1 <= len(stripped) <= MAX_MESSAGE_CODE_POINTS, where
        assert normalize_for_dedup(stripped), f"{where} has no alphanumeric content"


@pytest.mark.parametrize("name", SPLITS)
def test_ids_follow_split_format_and_are_unique(name: str) -> None:
    ids = [record["id"] for record in _records(name)]
    pattern = ID_PATTERNS[name]
    bad = [value for value in ids if not pattern.fullmatch(value)]
    assert not bad, f"{name} ids with wrong format: {bad[:5]}"
    repeated = sorted(value for value, count in Counter(ids).items() if count > 1)
    assert not repeated, f"{name} repeated ids: {repeated[:5]}"


def test_ids_do_not_collide_across_train_and_val() -> None:
    train_ids = {record["id"] for record in _records("train")}
    val_ids = {record["id"] for record in _records("val")}
    shared = sorted(train_ids & val_ids)
    assert not shared, f"ids shared by train and val: {shared[:5]}"


@pytest.mark.parametrize("name", SPLITS)
def test_no_duplicate_normalized_text_within_split(name: str) -> None:
    counts = Counter(_dedup_keys(name))
    duplicated = sorted(key for key, count in counts.items() if count > 1)
    assert not duplicated, f"{name} duplicate normalized texts: {duplicated[:5]}"


def test_no_duplicate_normalized_text_across_splits() -> None:
    overlap = set(_dedup_keys("train")) & set(_dedup_keys("val"))
    assert not overlap, f"train/val share normalized texts: {sorted(overlap)[:5]}"


def test_train_meets_binding_minimums() -> None:
    records = _records("train")
    counts = Counter(record["intent"] for record in records)
    for intent, minimum in _minimums(40, 60).items():
        assert counts[intent] >= minimum, f"train {intent}: {counts[intent]} < {minimum}"
    assert len(records) >= 460


def test_val_meets_binding_minimums() -> None:
    records = _records("val")
    counts = Counter(record["intent"] for record in records)
    for intent, minimum in _minimums(20, 40).items():
        assert counts[intent] >= minimum, f"val {intent}: {counts[intent]} < {minimum}"
    assert len(records) >= 240


def test_val_language_composition_meets_minimums() -> None:
    records = _records("val")
    counts = Counter(record["lang"] for record in records)
    total = len(records)
    for lang, percent in VAL_MIN_LANG_PERCENT.items():
        assert counts[lang] * 100 >= total * percent, (
            f"val lang {lang}: {counts[lang]}/{total} is below {percent}%"
        )


def test_leakage_checker_accepts_real_train_and_val(capsys) -> None:
    train_arg = str(SPLIT_PATHS["train"])
    val_arg = str(SPLIT_PATHS["val"])
    code = main(["--train", train_arg, "--val", val_arg])
    output = capsys.readouterr().out
    assert code == 0, output
    assert f"train total: {len(_records('train'))}" in output
    assert f"val total: {len(_records('val'))}" in output


def test_no_heldout_file_in_repository_data_directory() -> None:
    names = [path.name for path in CHAT_DATA_DIR.iterdir() if "held" in path.name.lower()]
    assert not names, f"held-out data must never be stored in the repository: {names}"
