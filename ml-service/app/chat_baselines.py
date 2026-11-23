"""Keyword and length baselines for the chat intent classifier.

These baselines are diagnostics. They do not use sklearn.

Limitations:
- ``KeywordBaseline`` rules were authored after the validation set had been
  inspected, so its validation accuracy is not an independent benchmark.
  Treat it as exploratory and never use its output to choose model parameters,
  a confidence threshold, or dataset labels.
- ``LengthBaseline`` only measures whether message length alone carries intent
  signal. It is never a feature of the primary classifier.
"""

from __future__ import annotations

from app.chat_text import INTENTS, normalize_for_dedup, normalize_for_model

KEYWORDS = {
    "OPENING_HOURS": (
        "buka",
        "tutup",
        "jam operasional",
        "opening",
        "closing",
        "open",
        "close",
        "last entry",
        "last order",
        "hours",
        "jadwal",
    ),
    "VENUE_AREA": (
        "area",
        "zona",
        "lantai",
        "rooftop",
        "indoor",
        "outdoor",
        "smoking",
        "dance floor",
        "toilet",
        "toilets",
        "wc",
        "panggung",
        "stage",
        "vip",
        "pintu masuk",
        "entrance",
    ),
    "MENU_INFORMATION": (
        "menu",
        "minuman",
        "makanan",
        "cocktail",
        "koktail",
        "mocktail",
        "bir",
        "beer",
        "wine",
        "snack",
        "cemilan",
        "food",
        "drink",
        "drinks",
        "beverages",
    ),
    "BOTTLE_KEEPING": (
        "botol",
        "bottle",
        "titip",
        "simpan",
        "keep",
        "storage",
    ),
    "MINIMUM_SPEND": (
        "minimum spend",
        "min spend",
        "minimum",
        "spending",
        "minimal",
    ),
    "PROMOTION": (
        "promo",
        "promos",
        "promotion",
        "diskon",
        "discount",
        "happy hour",
        "voucher",
        "deal",
        "deals",
        "penawaran",
        "offer",
        "member",
        "ladies night",
    ),
    "TABLE_AVAILABILITY": (
        "meja",
        "table",
        "kursi",
        "seat",
        "seats",
        "sofa",
        "booth",
        "kosong",
        "available",
        "tersedia",
        "muat",
        "free",
    ),
    "RESERVATION": (
        "reservasi",
        "reservation",
        "reservations",
        "reserve",
        "booking",
        "deposit",
        "walk in",
        "dp",
    ),
    "GENERAL_VENUE_INFORMATION": (
        "alamat",
        "address",
        "lokasi",
        "location",
        "parkir",
        "parking",
        "dress code",
        "wifi",
        "usia",
        "age",
        "akses",
        "payment",
        "pembayaran",
    ),
}


class KeywordBaseline:
    """Keyword-based baseline classifier."""

    def predict(self, text: str) -> str:
        """Predict intent using keyword matching.

        Matches whole words/phrases on padded normalized text.
        """
        padded = " " + normalize_for_dedup(text) + " "
        hits: set[str] = set()
        for intent, keywords in KEYWORDS.items():
            for kw in keywords:
                if (" " + kw + " ") in padded:
                    hits.add(intent)
                    break
        if not hits:
            return "OUT_OF_SCOPE"
        if len(hits) == 1:
            return hits.pop()
        return "ADVANCED_CONVERSATION"


class LengthBaseline:
    """Length-based diagnostic baseline classifier."""

    def __init__(self) -> None:
        self._mean_lengths: dict[str, float] = {}

    def fit(self, records: list[dict]) -> LengthBaseline:
        """Fit the baseline on training records.

        Computes mean length of normalize_for_model(text) per intent.
        Every intent in INTENTS must be present.
        """
        from collections import defaultdict

        lengths: defaultdict[str, list[int]] = defaultdict(list)
        for record in records:
            text = record["text"]
            intent = record["intent"]
            normalized = normalize_for_model(text)
            lengths[intent].append(len(normalized))

        missing = [intent for intent in INTENTS if intent not in lengths]
        if missing:
            raise ValueError(f"Missing intents in training data: {missing}")

        self._mean_lengths = {intent: sum(vals) / len(vals) for intent, vals in lengths.items()}
        return self

    def predict(self, text: str) -> str:
        """Predict intent by closest mean length.

        Ties broken by INTENTS order.
        """
        if not self._mean_lengths:
            raise ValueError("LengthBaseline not fitted")

        normalized = normalize_for_model(text)
        length = len(normalized)

        best_intent = None
        best_diff = float("inf")
        for intent in INTENTS:
            diff = abs(length - self._mean_lengths[intent])
            if diff < best_diff:
                best_diff = diff
                best_intent = intent
        return best_intent
