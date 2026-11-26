import json
from pathlib import Path

from scripts.check_chat_leakage import NOTE_LINE, main


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


def test_clean_files_exit_zero_and_print_counts(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(
        train,
        [
            _record("t1", "What time do you open?", "OPENING_HOURS", "en"),
            _record("t2", "Jam buka berapa?", "OPENING_HOURS", "id"),
            _record("t3", "Promo tonight apa?", "PROMOTION", "mixed"),
        ],
    )
    _write_jsonl(
        val,
        [
            _record("v1", "Where is the rooftop area?", "VENUE_AREA", "en"),
        ],
    )

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 0
    assert "train total: 3" in captured.out
    assert "val total: 1" in captured.out
    assert "train per-intent counts:" in captured.out
    assert "OPENING_HOURS: 2" in captured.out
    assert "PROMOTION: 1" in captured.out
    assert "train per-language counts:" in captured.out
    assert "en: 1" in captured.out
    assert "id: 1" in captured.out
    assert "mixed: 1" in captured.out
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_invalid_json_schema_intent_text_lang_source_and_missing_fields(
    tmp_path, capsys
) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(
        train,
        [
            "{bad json}",
            json.dumps(["not", "an", "object"]),
            _record("t1", "Valid text", intent="NOT_AN_INTENT"),
            _record("t2", "   "),
            _record("t3", "x" * 501),
            _record("t4", "Missing source", source=""),
            {
                "id": "t5",
                "text": "Missing intent/lang/source",
            },
            _record("t6", "Bad lang", lang="fr"),
            _record("", "Bad id"),
        ],
    )
    _write_jsonl(val, [_record("v1", "Valid validation row", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "INVALID_JSON" in captured.out
    assert "INVALID_SCHEMA" in captured.out
    assert "INVALID_INTENT" in captured.out
    assert "INVALID_TEXT_LENGTH" in captured.out
    assert "INVALID_LANG" in captured.out
    assert "INVALID_SOURCE" in captured.out
    assert "INVALID_ID" in captured.out
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_duplicate_ids_across_files_exit_one(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(train, [_record("dup-id", "Train text", "OPENING_HOURS", "en")])
    _write_jsonl(val, [_record("dup-id", "Val text", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "DUPLICATE_ID_CROSS_SPLIT" in captured.out


def test_cross_split_duplicate_detected(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(train, [_record("t1", "Jam buka hari ini?", "OPENING_HOURS", "id")])
    _write_jsonl(val, [_record("v1", "jam   buka hari ini?", "OPENING_HOURS", "id")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "EXACT_CROSS_SPLIT" in captured.out


def test_within_split_duplicate_detected(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(
        train,
        [
            _record("t1", "What time do you open?", "OPENING_HOURS", "en"),
            _record("t2", "what time do you open", "OPENING_HOURS", "en"),
        ],
    )
    _write_jsonl(val, [_record("v1", "Where is the bar?", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "DUPLICATE_WITHIN_SPLIT" in captured.out


def test_label_conflict_detected(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(
        train,
        [
            _record("t1", "Do you have promotions tonight?", "PROMOTION", "en"),
            _record("t2", "Do you have promotions tonight", "OPENING_HOURS", "en"),
        ],
    )
    _write_jsonl(val, [_record("v1", "Where is the lounge?", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "LABEL_CONFLICT" in captured.out


def test_exit_code_two_for_unreadable_file(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    missing_val = tmp_path / "missing-val.jsonl"
    _write_jsonl(train, [_record("t1", "Valid text", "OPENING_HOURS", "en")])

    code = main(["--train", str(train), "--val", str(missing_val)])
    captured = capsys.readouterr()

    assert code == 2
    assert "unreadable file" in captured.err.lower()
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_exit_code_two_for_invalid_arguments(capsys) -> None:
    code = main(["--train", "only-train.jsonl"])
    captured = capsys.readouterr()

    assert code == 2
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_heldout_output_never_includes_heldout_text(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    heldout = tmp_path / "heldout.jsonl"
    # Use a variant that normalizes to the same dedup key as train's "Shared question"
    secret_text = "shared question"
    malformed_secret = "SECRET_MALFORMED_HELDOUT_77"

    _write_jsonl(train, [_record("t1", "Shared question", "OPENING_HOURS", "en")])
    _write_jsonl(val, [_record("v1", "Another question", "VENUE_AREA", "en")])
    _write_jsonl(
        heldout,
        [
            {"text": secret_text, "intent": "OPENING_HOURS"},
            "{" + malformed_secret,
        ],
    )

    code = main(
        [
            "--train",
            str(train),
            "--val",
            str(val),
            "--heldout",
            str(heldout),
        ]
    )
    captured = capsys.readouterr()
    combined = captured.out + captured.err

    assert code == 1
    assert secret_text not in combined
    assert malformed_secret not in combined
    assert "INVALID_JSON heldout:2" in captured.out
    assert "EXACT_CROSS_SPLIT" in captured.out
    assert "t1" in captured.out
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_source_other_is_invalid(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(train, [_record("t1", "Valid text", source="other")])
    _write_jsonl(val, [_record("v1", "Valid val", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "INVALID_SOURCE" in captured.out


def test_heldout_only_text_and_intent_no_overlap_exit_zero(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    heldout = tmp_path / "heldout.jsonl"
    _write_jsonl(train, [_record("t1", "Train question", "OPENING_HOURS", "en")])
    _write_jsonl(val, [_record("v1", "Val question", "VENUE_AREA", "en")])
    _write_jsonl(
        heldout,
        [
            {"text": "Heldout only question", "intent": "PROMOTION"},
            {"text": "Another heldout question", "intent": "MENU_INFORMATION"},
        ],
    )

    code = main(
        ["--train", str(train), "--val", str(val), "--heldout", str(heldout)]
    )
    captured = capsys.readouterr()

    assert code == 0
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_heldout_label_conflict_names_both_intents_without_text(
    tmp_path, capsys
) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    heldout = tmp_path / "heldout.jsonl"
    # Use a variant that normalizes to the same dedup key as train's "Same meaning text"
    secret_text = "same meaning text"

    _write_jsonl(train, [_record("t1", "Same meaning text", "PROMOTION", "en")])
    _write_jsonl(val, [_record("v1", "Other text", "VENUE_AREA", "en")])
    _write_jsonl(heldout, [{"text": secret_text, "intent": "OPENING_HOURS"}])

    code = main(
        ["--train", str(train), "--val", str(val), "--heldout", str(heldout)]
    )
    captured = capsys.readouterr()
    combined = captured.out + captured.err

    assert code == 1
    assert "LABEL_CONFLICT" in captured.out
    assert "PROMOTION" in captured.out
    assert "OPENING_HOURS" in captured.out
    assert secret_text not in combined


def test_heldout_exact_duplicate_reports_train_id_and_line(
    tmp_path, capsys
) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    heldout = tmp_path / "heldout.jsonl"
    # Use a variant that normalizes to the same dedup key as train's "Duplicate text"
    secret_text = "duplicate text"

    _write_jsonl(train, [_record("t1", "Duplicate text", "OPENING_HOURS", "en")])
    _write_jsonl(val, [_record("v1", "Val text", "VENUE_AREA", "en")])
    _write_jsonl(heldout, [{"text": secret_text, "intent": "OPENING_HOURS"}])

    code = main(
        ["--train", str(train), "--val", str(val), "--heldout", str(heldout)]
    )
    captured = capsys.readouterr()
    combined = captured.out + captured.err

    assert code == 1
    assert "EXACT_CROSS_SPLIT" in captured.out
    assert "t1" in captured.out
    assert "train" in captured.out
    assert secret_text not in combined


def test_non_utf8_train_file_returns_exit_two(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    train.write_bytes(b"\xff\xfe\x00")
    _write_jsonl(val, [_record("v1", "Valid val", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 2
    assert "ERROR: file is not valid UTF-8" in captured.err
    assert "Traceback" not in captured.out
    assert "Traceback" not in captured.err
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_non_utf8_heldout_file_returns_exit_two(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    heldout = tmp_path / "heldout.jsonl"
    _write_jsonl(train, [_record("t1", "Valid train", "OPENING_HOURS", "en")])
    _write_jsonl(val, [_record("v1", "Valid val", "VENUE_AREA", "en")])
    heldout.write_bytes(b"\xff\xfe\x00")

    code = main(
        ["--train", str(train), "--val", str(val), "--heldout", str(heldout)]
    )
    captured = capsys.readouterr()

    assert code == 2
    assert "ERROR: file is not valid UTF-8" in captured.err
    assert "Traceback" not in captured.out
    assert "Traceback" not in captured.err
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_invalid_records_not_counted_in_language_counts(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(
        train,
        [
            _record("t1", "Valid english text", "OPENING_HOURS", "en"),
            _record("t2", "Bad language", "OPENING_HOURS", "fr"),
        ],
    )
    _write_jsonl(val, [_record("v1", "Valid val", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "INVALID_LANG" in captured.out
    assert "train total: 1" in captured.out
    counts_block = captured.out.split("val total:", 1)[0]
    assert "fr" not in counts_block
    assert "en: 1" in counts_block
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_train_record_with_no_alnum_content_is_invalid(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    _write_jsonl(
        train,
        [
            _record("t1", "Valid text", "OPENING_HOURS", "en"),
            _record("t2", "???", "OPENING_HOURS", "en"),
        ],
    )
    _write_jsonl(val, [_record("v1", "Valid val", "VENUE_AREA", "en")])

    code = main(["--train", str(train), "--val", str(val)])
    captured = capsys.readouterr()

    assert code == 1
    assert "INVALID_TEXT_CONTENT train:2 text has no alphanumeric content" in captured.out
    assert "train total: 1" in captured.out
    assert captured.out.rstrip().endswith(NOTE_LINE)


def test_heldout_record_with_no_alnum_content_is_invalid(tmp_path, capsys) -> None:
    train = tmp_path / "train.jsonl"
    val = tmp_path / "val.jsonl"
    heldout = tmp_path / "heldout.jsonl"
    _write_jsonl(train, [_record("t1", "Valid train", "OPENING_HOURS", "en")])
    _write_jsonl(val, [_record("v1", "Valid val", "VENUE_AREA", "en")])
    _write_jsonl(
        heldout,
        [
            {"text": "???", "intent": "OPENING_HOURS"},
            {"text": "Valid heldout", "intent": "PROMOTION"},
        ],
    )

    code = main(
        ["--train", str(train), "--val", str(val), "--heldout", str(heldout)]
    )
    captured = capsys.readouterr()
    combined = captured.out + captured.err

    assert code == 1
    assert "INVALID_TEXT_CONTENT heldout:1 text has no alphanumeric content" in captured.out
    assert "???" not in combined
    assert captured.out.rstrip().endswith(NOTE_LINE)
