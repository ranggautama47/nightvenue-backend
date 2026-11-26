"""Check chat intent dataset files for schema issues, duplicates, and leakage."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from app.chat_text import INTENTS, MAX_MESSAGE_CODE_POINTS, normalize_for_dedup, normalize_for_model

REQUIRED_FIELDS = ("id", "text", "intent", "lang", "source")
HELDOUT_REQUIRED_FIELDS = ("text", "intent")
VALID_LANGS = {"id", "en", "mixed"}
VALID_SOURCES = {"synthetic", "hand_labeled"}
NOTE_LINE = "NOTE: near-duplicate TF-IDF cosine check is NOT included in this version."

NEAR_DUP_THRESHOLD = 0.85


def is_near_duplicate(score: float, threshold: float = NEAR_DUP_THRESHOLD) -> bool:
    """Check if a similarity score indicates a near-duplicate."""
    return score >= threshold


def _get_vectorizer():
    """Lazy import of build_char_vectorizer to avoid heavy deps at module load time."""
    from app.chat_training import build_char_vectorizer
    return build_char_vectorizer()


def find_near_duplicates(
    train_records: Sequence[object],
    val_records: Sequence[object],
    threshold: float = NEAR_DUP_THRESHOLD,
) -> list[tuple[str, str, float]]:
    """Find near-duplicate pairs between train and val records.

    Uses TF-IDF char_wb 2-4 cosine similarity on normalize_for_model text.
    Never uses labels. Only compares train vs val (not heldout).

    Args:
        train_records: Sequence of objects with 'text' and 'record_id' attributes/keys.
        val_records: Sequence of objects with 'text' and 'record_id' attributes/keys.

    Returns:
        List of (train_id, val_id, score) tuples sorted by (-score, train_id, val_id).
    """
    def _get_text(r: object) -> str:
        if isinstance(r, dict):
            return r["text"]
        return r.text

    def _get_id(r: object) -> str:
        if isinstance(r, dict):
            return r["id"]
        return r.record_id

    # Extract texts and IDs
    train_texts = [normalize_for_model(_get_text(r)) for r in train_records]
    val_texts = [normalize_for_model(_get_text(r)) for r in val_records]
    train_ids = [_get_id(r) for r in train_records]
    val_ids = [_get_id(r) for r in val_records]

    # Fit the label-free vectorizer on combined train + val texts so that
    # validation-only n-grams are represented, then slice the rows back apart.
    vectorizer = _get_vectorizer()
    combined_texts = train_texts + val_texts
    combined_matrix = vectorizer.fit_transform(combined_texts)

    train_count = len(train_texts)
    X_train = combined_matrix[:train_count]
    X_val = combined_matrix[train_count:]

    # Compute cosine similarity: (X_val @ X_train.T)
    # Since TF-IDF vectors are L2-normalized, dot product = cosine
    cosine_matrix = (X_val @ X_train.T).toarray()

    pairs: list[tuple[str, str, float]] = []
    for i, val_id in enumerate(val_ids):
        for j, train_id in enumerate(train_ids):
            score = float(cosine_matrix[i, j])
            if is_near_duplicate(score, threshold):
                pairs.append((train_id, val_id, score))

    # Sort by (-score, train_id, val_id)
    pairs.sort(key=lambda x: (-x[2], x[0], x[1]))
    return pairs


@dataclass(frozen=True)
class Record:
    split: str
    line_no: int
    record_id: str
    text: str
    intent: str
    lang: str
    source: str
    dedup_key: str


@dataclass(frozen=True)
class Violation:
    category: str
    split: str
    line_no: int
    message: str
    confidential: bool = False


def _is_non_empty_str(value: object) -> bool:
    return isinstance(value, str) and value != ""


def _record_label(record: Record) -> str:
    if record.split == "heldout":
        return f"{record.split}:{record.line_no}"
    if record.record_id:
        return f"{record.split}:{record.line_no} ({record.record_id})"
    return f"{record.split}:{record.line_no}"


def _record_ref(record: Record) -> str:
    if record.split == "heldout":
        return f"{record.split} line {record.line_no}"
    if record.record_id:
        return f"{record.split} id {record.record_id} line {record.line_no}"
    return f"{record.split} line {record.line_no}"


def _print_counts(records: Sequence[Record], split: str) -> None:
    intent_counts = Counter(record.intent for record in records)
    lang_counts = Counter(record.lang for record in records)
    print(f"{split} total: {len(records)}")
    print(f"{split} per-intent counts:")
    for intent in INTENTS:
        if intent_counts[intent]:
            print(f"  {intent}: {intent_counts[intent]}")
    print(f"{split} per-language counts:")
    for lang in sorted(lang_counts):
        print(f"  {lang}: {lang_counts[lang]}")


def _format_violation(violation: Violation) -> str:
    return violation.message


def _load_split(
    split: str,
    path: Path,
    confidential: bool,
) -> tuple[list[Record], list[Violation]]:
    records: list[Record] = []
    violations: list[Violation] = []
    required_fields = HELDOUT_REQUIRED_FIELDS if confidential else REQUIRED_FIELDS

    with path.open("r", encoding="utf-8") as handle:
        for line_no, raw_line in enumerate(handle, start=1):
            line = raw_line.rstrip("\n\r")
            per_record: list[Violation] = []

            if line.strip() == "":
                per_record.append(
                    Violation(
                        "INVALID_SCHEMA",
                        split,
                        line_no,
                        f"INVALID_SCHEMA {split}:{line_no} empty JSONL line",
                        confidential=confidential,
                    )
                )
                violations.extend(per_record)
                continue

            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                per_record.append(
                    Violation(
                        "INVALID_JSON",
                        split,
                        line_no,
                        f"INVALID_JSON {split}:{line_no} invalid JSON",
                        confidential=confidential,
                    )
                )
                violations.extend(per_record)
                continue

            if not isinstance(payload, dict):
                per_record.append(
                    Violation(
                        "INVALID_SCHEMA",
                        split,
                        line_no,
                        f"INVALID_SCHEMA {split}:{line_no} record must be a JSON object",
                        confidential=confidential,
                    )
                )
                violations.extend(per_record)
                continue

            missing = [field for field in required_fields if field not in payload]
            if missing:
                fields = ",".join(missing)
                per_record.append(
                    Violation(
                        "INVALID_SCHEMA",
                        split,
                        line_no,
                        f"INVALID_SCHEMA {split}:{line_no} missing fields {fields}",
                        confidential=confidential,
                    )
                )

            record_id = payload.get("id", "")
            text = payload.get("text", "")
            intent = payload.get("intent", "")
            lang = payload.get("lang", "")
            source = payload.get("source", "")

            if not confidential:
                if "id" in payload and not _is_non_empty_str(record_id):
                    per_record.append(
                        Violation(
                            "INVALID_ID",
                            split,
                            line_no,
                            f"INVALID_ID {split}:{line_no} id must be a non-empty string",
                            confidential=confidential,
                        )
                    )
            elif "id" in payload and not _is_non_empty_str(record_id):
                record_id = ""

            if "text" in payload:
                if not isinstance(text, str):
                    per_record.append(
                        Violation(
                            "INVALID_SCHEMA",
                            split,
                            line_no,
                            f"INVALID_SCHEMA {split}:{line_no} text must be a string",
                            confidential=confidential,
                        )
                    )
                else:
                    stripped = text.strip()
                    length = len(stripped)
                    if length < 1 or length > MAX_MESSAGE_CODE_POINTS:
                        limit = MAX_MESSAGE_CODE_POINTS
                        msg = (
                            f"INVALID_TEXT_LENGTH {split}:{line_no} "
                            f"text length must be 1..{limit}"
                        )
                        per_record.append(
                            Violation(
                                "INVALID_TEXT_LENGTH",
                                split,
                                line_no,
                                msg,
                                confidential=confidential,
                            )
                        )
                    else:
                        # Check for empty dedup key (no alphanumeric content)
                        dedup_key = normalize_for_dedup(stripped)
                        if not dedup_key:
                            msg = (
                                f"INVALID_TEXT_CONTENT {split}:{line_no} "
                                f"text has no alphanumeric content"
                            )
                            per_record.append(
                                Violation(
                                    "INVALID_TEXT_CONTENT",
                                    split,
                                    line_no,
                                    msg,
                                    confidential=confidential,
                                )
                            )

            if "intent" in payload:
                if not _is_non_empty_str(intent) or intent not in INTENTS:
                    per_record.append(
                        Violation(
                            "INVALID_INTENT",
                            split,
                            line_no,
                            f"INVALID_INTENT {split}:{line_no} invalid intent",
                            confidential=confidential,
                        )
                    )

            if not confidential:
                if "lang" in payload and (
                    not _is_non_empty_str(lang) or lang not in VALID_LANGS
                ):
                    per_record.append(
                        Violation(
                            "INVALID_LANG",
                            split,
                            line_no,
                            f"INVALID_LANG {split}:{line_no} invalid lang",
                            confidential=confidential,
                        )
                    )
                if "source" in payload and (
                    not _is_non_empty_str(source) or source not in VALID_SOURCES
                ):
                    per_record.append(
                        Violation(
                            "INVALID_SOURCE",
                            split,
                            line_no,
                            f"INVALID_SOURCE {split}:{line_no} invalid source",
                            confidential=confidential,
                        )
                    )

            violations.extend(per_record)
            if per_record:
                continue

            record_text = text if isinstance(text, str) else ""
            record_intent = intent if isinstance(intent, str) else ""
            record_lang = lang if isinstance(lang, str) and not confidential else ""
            if not confidential and isinstance(lang, str):
                record_lang = lang
            record_source = source if isinstance(source, str) and not confidential else ""
            if not confidential and isinstance(source, str):
                record_source = source
            record_id_str = record_id if isinstance(record_id, str) and not confidential else ""
            if not confidential and isinstance(record_id, str):
                record_id_str = record_id
            records.append(
                Record(
                    split=split,
                    line_no=line_no,
                    record_id=record_id_str,
                    text=record_text,
                    intent=record_intent,
                    lang=record_lang,
                    source=record_source,
                    dedup_key=normalize_for_dedup(record_text),
                )
            )

    return records, violations


def _add_id_cross_split(
    violations: list[Violation],
    current: Record,
    previous: Record,
) -> None:
    if previous.split == current.split:
        msg = (
            f"DUPLICATE_ID {current.split}:{current.line_no} ({current.record_id}) "
            f"conflicts with {previous.split}:{previous.line_no}"
        )
        violations.append(
            Violation("DUPLICATE_ID", current.split, current.line_no, msg)
        )
        return

    msg_cur = (
        f"DUPLICATE_ID_CROSS_SPLIT {current.split}:{current.line_no} "
        f"({current.record_id}) conflicts with {previous.split}:{previous.line_no}"
    )
    msg_prev = (
        f"DUPLICATE_ID_CROSS_SPLIT {previous.split}:{previous.line_no} "
        f"({previous.record_id}) conflicts with {current.split}:{current.line_no}"
    )
    violations.append(
        Violation("DUPLICATE_ID_CROSS_SPLIT", current.split, current.line_no, msg_cur)
    )
    violations.append(
        Violation("DUPLICATE_ID_CROSS_SPLIT", previous.split, previous.line_no, msg_prev)
    )


def _add_cross_duplicate(
    violations: list[Violation],
    category: str,
    current: Record,
    peer: Record,
) -> None:
    if category == "EXACT_CROSS_SPLIT":
        msg = (
            f"{category} {_record_label(current)} "
            f"duplicate of {_record_ref(peer)}"
        )
        violations.append(
            Violation(category, current.split, current.line_no, msg, current.split == "heldout")
        )
        msg_peer = (
            f"{category} {_record_label(peer)} "
            f"duplicate of {_record_ref(current)}"
        )
        violations.append(
            Violation(category, peer.split, peer.line_no, msg_peer, peer.split == "heldout")
        )
        return

    cur_intent = current.intent if current.intent in INTENTS else ""
    peer_intent = peer.intent if peer.intent in INTENTS else ""
    msg = (
        f"{category} {_record_label(current)} intent {cur_intent} "
        f"conflicts with {_record_ref(peer)} intent {peer_intent}"
    )
    violations.append(
        Violation(category, current.split, current.line_no, msg, current.split == "heldout")
    )
    msg_peer = (
        f"{category} {_record_label(peer)} intent {peer_intent} "
        f"conflicts with {_record_ref(current)} intent {cur_intent}"
    )
    violations.append(
        Violation(category, peer.split, peer.line_no, msg_peer, peer.split == "heldout")
    )


def check_leakage(
    train_records: Sequence[Record],
    val_records: Sequence[Record],
    heldout_records: Sequence[Record] | None = None,
) -> list[Violation]:
    violations: list[Violation] = []
    all_splits: list[tuple[str, Sequence[Record]]] = [
        ("train", train_records),
        ("val", val_records),
    ]
    if heldout_records is not None:
        all_splits.append(("heldout", heldout_records))

    seen_ids: dict[str, Record] = {}
    for _, records in [("train", train_records), ("val", val_records)]:
        for record in records:
            if not record.record_id:
                continue
            previous = seen_ids.get(record.record_id)
            if previous is None:
                seen_ids[record.record_id] = record
                continue
            _add_id_cross_split(violations, record, previous)

    by_split_key: dict[tuple[str, str], list[Record]] = defaultdict(list)
    by_key: dict[str, list[Record]] = defaultdict(list)
    for _, records in all_splits:
        for record in records:
            if not record.dedup_key:
                continue
            by_split_key[(record.split, record.dedup_key)].append(record)
            by_key[record.dedup_key].append(record)

    for records in by_split_key.values():
        if len(records) < 2:
            continue
        for record in records:
            msg = f"DUPLICATE_WITHIN_SPLIT {_record_label(record)}"
            violations.append(
                Violation(
                    "DUPLICATE_WITHIN_SPLIT",
                    record.split,
                    record.line_no,
                    msg,
                    record.split == "heldout",
                )
            )

    for records in by_key.values():
        if len(records) < 2:
            continue
        split_names = {record.split for record in records}
        intents = {record.intent for record in records if record.intent}
        if len(split_names) > 1:
            for index, current in enumerate(records):
                for peer in records[index + 1 :]:
                    if current.split == peer.split:
                        continue
                    if current.intent == peer.intent:
                        _add_cross_duplicate(violations, "EXACT_CROSS_SPLIT", current, peer)
        if len(intents) > 1:
            for index, current in enumerate(records):
                for peer in records[index + 1 :]:
                    if current.intent == peer.intent:
                        continue
                    if current.split == peer.split:
                        cur_intent = current.intent if current.intent in INTENTS else ""
                        peer_intent = peer.intent if peer.intent in INTENTS else ""
                        msg_cur = (
                            f"LABEL_CONFLICT {_record_label(current)} intent {cur_intent} "
                            f"conflicts with {_record_ref(peer)} intent {peer_intent}"
                        )
                        msg_peer = (
                            f"LABEL_CONFLICT {_record_label(peer)} intent {peer_intent} "
                            f"conflicts with {_record_ref(current)} intent {cur_intent}"
                        )
                        conf = current.split == "heldout"
                        violations.append(
                            Violation(
                                "LABEL_CONFLICT",
                                current.split,
                                current.line_no,
                                msg_cur,
                                conf,
                            )
                        )
                        violations.append(
                            Violation(
                                "LABEL_CONFLICT",
                                peer.split,
                                peer.line_no,
                                msg_peer,
                                conf,
                            )
                        )
                    else:
                        _add_cross_duplicate(violations, "LABEL_CONFLICT", current, peer)

    unique: list[Violation] = []
    seen_messages: set[str] = set()
    for violation in violations:
        if violation.message in seen_messages:
            continue
        seen_messages.add(violation.message)
        unique.append(violation)
    return unique


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m scripts.check_chat_leakage",
        description="Check chat train/val datasets for leakage and schema violations.",
    )
    parser.add_argument("--train", required=True, help="Path to train JSONL file")
    parser.add_argument("--val", required=True, help="Path to validation JSONL file")
    parser.add_argument("--heldout", help="Path to held-out JSONL file")
    parser.add_argument(
        "--near-dup",
        action="store_true",
        help="Enable near-duplicate TF-IDF cosine check",
    )

    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        code = exc.code
        print(NOTE_LINE)
        return code if isinstance(code, int) else 2

    # --near-dup requires both --train and --val (already required by parser)
    # but if somehow missing, fall through to existing argument-error path (exit 2)

    train_path = Path(args.train)
    val_path = Path(args.val)
    heldout_path = Path(args.heldout) if args.heldout else None

    try:
        train_records, train_violations = _load_split("train", train_path, False)
        val_records, val_violations = _load_split("val", val_path, False)
        heldout_records: list[Record] | None = None
        heldout_violations: list[Violation] = []
        if heldout_path is not None:
            heldout_records, heldout_violations = _load_split("heldout", heldout_path, True)
    except UnicodeDecodeError:
        print("ERROR: file is not valid UTF-8", file=sys.stderr)
        print(NOTE_LINE)
        return 2
    except OSError as exc:
        print(f"ERROR: unreadable file: {exc}", file=sys.stderr)
        print(NOTE_LINE)
        return 2

    violations = [
        *train_violations,
        *val_violations,
        *heldout_violations,
        *check_leakage(train_records, val_records, heldout_records),
    ]

    # Near-duplicate check (only train vs val, never heldout)
    near_dup_pairs: list[tuple[str, str, float]] = []
    if args.near_dup:
        near_dup_pairs = find_near_duplicates(train_records, val_records)
        for train_id, val_id, score in near_dup_pairs:
            print(f"NEAR_DUPLICATE train={train_id} val={val_id} cosine={score:.4f}")
        print(
        f"near-duplicate pairs: {len(near_dup_pairs)} (threshold 0.85, TF-IDF char_wb 2-4, "
        "train vs val)"
    )

    _print_counts(train_records, "train")
    _print_counts(val_records, "val")

    for violation in violations:
        print(_format_violation(violation))

    if not args.near_dup:
        print(NOTE_LINE)

    # Exit 1 if any existing violation OR any near-duplicate pair
    if violations or near_dup_pairs:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
