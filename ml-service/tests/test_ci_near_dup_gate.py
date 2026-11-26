"""Unit tests for scripts/ci_near_dup_gate.py."""

from __future__ import annotations

import pytest

from scripts.ci_near_dup_gate import APPROVED_PAIR, decide, parse_pairs


def test_parse_and_decide_approved_only(capsys) -> None:
    stdout = (
        "NEAR_DUPLICATE train=tr-0723 val=va-0861 cosine=0.9059\n"
        "near-duplicate pairs: 1 (threshold 0.85, TF-IDF char_wb 2-4, train vs val)\n"
    )
    pairs = parse_pairs(stdout)
    assert pairs == {APPROVED_PAIR}
    assert decide(1, pairs) == 0
    captured = capsys.readouterr()
    assert "tr-0723" in captured.out
    assert "va-0861" in captured.out


def test_decide_extra_pair_fails() -> None:
    stdout = (
        "NEAR_DUPLICATE train=tr-0723 val=va-0861 cosine=0.9059\n"
        "NEAR_DUPLICATE train=tr-0001 val=va-0002 cosine=0.8800\n"
    )
    pairs = parse_pairs(stdout)
    assert decide(1, pairs) != 0


def test_decide_none_clean_passes() -> None:
    pairs = parse_pairs("near-duplicate pairs: 0\n")
    assert pairs == set()
    assert decide(0, pairs) == 0


def test_parse_garbage_raises() -> None:
    with pytest.raises(ValueError):
        parse_pairs("NEAR_DUPLICATE malformed line without fields\n")


def test_decide_error_exit_code_fails() -> None:
    assert decide(2, set()) != 0
    assert decide(1, set()) != 0
