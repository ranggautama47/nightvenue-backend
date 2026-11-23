"""Tests for chat_baselines module."""

from __future__ import annotations

import pytest

from app.chat_baselines import KEYWORDS, KeywordBaseline, LengthBaseline
from app.chat_text import INTENTS, LOCAL_INTENTS


class TestKeywordBaseline:
    def test_single_intent_hit(self) -> None:
        baseline = KeywordBaseline()
        # "buka" is a keyword for OPENING_HOURS
        assert baseline.predict("jam buka berapa") == "OPENING_HOURS"
        # "promo" is a keyword for PROMOTION
        assert baseline.predict("ada promo hari ini") == "PROMOTION"
        # "menu" is a keyword for MENU_INFORMATION
        assert baseline.predict("menu apa saja") == "MENU_INFORMATION"

    def test_no_hit_returns_out_of_scope(self) -> None:
        baseline = KeywordBaseline()
        # No keywords from any intent
        assert baseline.predict("tolong carikan hotel murah") == "OUT_OF_SCOPE"
        assert baseline.predict("berapa harga tiket bioskop") == "OUT_OF_SCOPE"

    def test_two_intents_hit_returns_advanced(self) -> None:
        baseline = KeywordBaseline()
        # "buka" (OPENING_HOURS) + "promo" (PROMOTION)
        assert baseline.predict("jam buka dan promo apa") == "ADVANCED_CONVERSATION"
        # "menu" (MENU_INFORMATION) + "meja" (TABLE_AVAILABILITY)
        assert baseline.predict("menu dan meja kosong") == "ADVANCED_CONVERSATION"

    def test_whole_word_matching(self) -> None:
        baseline = KeywordBaseline()
        # "minimalis" contains "minimal" but not as a whole word, so no hit.
        assert baseline.predict("saya minimalis") == "OUT_OF_SCOPE"
        # "promotor" contains "promo" but not as a whole word, so no hit.
        assert baseline.predict("promotor acara") == "OUT_OF_SCOPE"
        # Counter-check: the same keyword as a standalone word does hit.
        assert baseline.predict("minimal berapa") == "MINIMUM_SPEND"

    def test_case_insensitive_via_normalize(self) -> None:
        baseline = KeywordBaseline()
        assert baseline.predict("JAM BUKA") == "OPENING_HOURS"
        assert baseline.predict("Promo") == "PROMOTION"

    def test_punctuation_handled(self) -> None:
        baseline = KeywordBaseline()
        assert baseline.predict("jam buka?") == "OPENING_HOURS"
        assert baseline.predict("promo!") == "PROMOTION"


class TestLengthBaseline:
    def test_fit_and_predict_uses_closest_mean_length(self) -> None:
        # Distinct known mean lengths; fit requires every intent present.
        lengths = {intent: 30 for intent in INTENTS}
        lengths["OPENING_HOURS"] = 3
        lengths["PROMOTION"] = 11
        records = [
            {
                "text": "x" * lengths[intent],
                "intent": intent,
                "id": f"{intent}-1",
                "lang": "en",
                "source": "synthetic",
            }
            for intent in INTENTS
        ]

        baseline = LengthBaseline().fit(records)
        assert baseline.predict("xxx") == "OPENING_HOURS"
        assert baseline.predict("x" * 11) == "PROMOTION"

    def test_tie_break_by_intents_order(self) -> None:
        # Create records where all intents have the same mean length
        records = []
        for intent in INTENTS:
            records.append({
                "text": "same length",  # All same length
                "intent": intent,
                "id": f"{intent}-1",
                "lang": "en",
                "source": "synthetic",
            })

        baseline = LengthBaseline().fit(records)
        # With same length, should return first intent in INTENTS order
        pred = baseline.predict("query")
        assert pred == INTENTS[0]

    def test_missing_intent_raises(self) -> None:
        records = [
            {
                "text": "text",
                "intent": "OPENING_HOURS",
                "id": "1",
                "lang": "en",
                "source": "synthetic",
            },
        ]
        baseline = LengthBaseline()
        with pytest.raises(ValueError) as exc:
            baseline.fit(records)
        assert "Missing intents" in str(exc.value)

    def test_predict_without_fit_raises(self) -> None:
        baseline = LengthBaseline()
        with pytest.raises(ValueError) as exc:
            baseline.predict("text")
        assert "not fitted" in str(exc.value).lower()


class TestKeywords:
    def test_keywords_keys_equal_local_intents(self) -> None:
        assert set(KEYWORDS.keys()) == set(LOCAL_INTENTS)
