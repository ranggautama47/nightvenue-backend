from app.chat_text import (
    INTENTS,
    LOCAL_INTENTS,
    MAX_MESSAGE_CODE_POINTS,
    normalize_for_dedup,
    normalize_for_model,
)


def test_intents_are_ordered_and_unique() -> None:
    assert INTENTS == (
        "OPENING_HOURS",
        "VENUE_AREA",
        "MENU_INFORMATION",
        "BOTTLE_KEEPING",
        "MINIMUM_SPEND",
        "PROMOTION",
        "TABLE_AVAILABILITY",
        "RESERVATION",
        "GENERAL_VENUE_INFORMATION",
        "ADVANCED_CONVERSATION",
        "OUT_OF_SCOPE",
    )
    assert len(INTENTS) == 11
    assert len(set(INTENTS)) == 11


def test_local_intents_are_first_nine() -> None:
    assert LOCAL_INTENTS == INTENTS[:9]
    assert len(LOCAL_INTENTS) == 9
    assert "ADVANCED_CONVERSATION" not in LOCAL_INTENTS
    assert "OUT_OF_SCOPE" not in LOCAL_INTENTS


def test_model_normalization_preserves_digits_and_punctuation() -> None:
    text = "  Jam 2.30,  meja #4?  "
    assert normalize_for_model(text) == "jam 2.30, meja #4?"


def test_model_normalization_distinguishes_digit_sequences() -> None:
    assert normalize_for_model("jam 2") != normalize_for_model("jam 22")
    assert normalize_for_model("jam 2.30") != normalize_for_model("jam 230")


def test_dedup_normalization_collapses_case_spacing_and_punctuation_variants() -> None:
    variants = [
        "Jam buka?",
        "  jam   buka!! ",
        "JAM-buka",
        "jam, buka.",
    ]
    normalized = {normalize_for_dedup(text) for text in variants}
    assert normalized == {"jam buka"}


def test_normalization_is_idempotent() -> None:
    samples = [
        "  Hello,   WORLD!!  123 ",
        "Jam 2.30 di Sky Lantern",
        "MIXED-case & punctuation???",
    ]
    for text in samples:
        model_once = normalize_for_model(text)
        model_twice = normalize_for_model(model_once)
        assert model_once == model_twice

        dedup_once = normalize_for_dedup(text)
        dedup_twice = normalize_for_dedup(dedup_once)
        assert dedup_once == dedup_twice


def test_dedup_normalization_handles_unicode_alnum() -> None:
    # Non-ASCII alnum characters are preserved (not stripped to empty)
    assert normalize_for_dedup("café buka") == "café buka"
    assert normalize_for_dedup("caf buka") == "caf buka"
    assert normalize_for_dedup("café buka") != normalize_for_dedup("caf buka")

    # Different non-Latin strings give different non-empty keys
    assert normalize_for_dedup("你好") == "你好"
    assert normalize_for_dedup("再见") == "再见"
    assert normalize_for_dedup("你好") != normalize_for_dedup("再见")

    # Emoji-only or punctuation-only gives empty string
    assert normalize_for_dedup("😀🎉") == ""
    assert normalize_for_dedup("???") == ""
    assert normalize_for_dedup("!!!") == ""

    # Digit sequences still distinguished (existing behavior)
    assert normalize_for_dedup("jam 2.30") != normalize_for_dedup("jam 230")
    assert normalize_for_dedup("jam 2") != normalize_for_dedup("jam 22")


def test_max_message_code_points_constant() -> None:
    assert MAX_MESSAGE_CODE_POINTS == 500
