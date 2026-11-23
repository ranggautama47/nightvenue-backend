"""Shared text normalization and intent constants for the chat intent pipeline."""

from __future__ import annotations

import re
import unicodedata

INTENTS: tuple[str, ...] = (
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

LOCAL_INTENTS: tuple[str, ...] = INTENTS[:9]

MAX_MESSAGE_CODE_POINTS = 500

_WHITESPACE_RE = re.compile(r"\s+")


def normalize_for_model(text: str) -> str:
    """Normalize user text for model input.

    Applies Unicode NFKC normalization, lowercasing, whitespace collapsing, and
    stripping. Digits and punctuation are preserved.
    """

    normalized = unicodedata.normalize("NFKC", text).lower()
    normalized = _WHITESPACE_RE.sub(" ", normalized)
    return normalized.strip()


def normalize_for_dedup(text: str) -> str:
    """Normalize text for duplicate and conflict detection.

    Applies model normalization, replaces every non-alphanumeric character with a
    space, collapses whitespace, and strips.
    """

    normalized = normalize_for_model(text)
    normalized = "".join(ch if ch.isalnum() else " " for ch in normalized)
    normalized = _WHITESPACE_RE.sub(" ", normalized)
    return normalized.strip()
