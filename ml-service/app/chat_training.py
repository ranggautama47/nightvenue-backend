"""Chat intent classifier training pipeline.

This module provides the training pipeline for the fixed Model K chat intent
classifier: FeatureUnion(TF-IDF char_wb 2-4 + TF-IDF word 1-2) with
LogisticRegression. No cross-validation or hyperparameter search is used.

SECURITY NOTE: Only load artifacts you built yourself. joblib/pickle is not
safe for untrusted files.
"""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

import joblib
import numpy as np
import scipy
import sklearn
import threadpoolctl
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import FeatureUnion, Pipeline
from threadpoolctl import threadpool_limits

from app.chat_text import (
    INTENTS,
    MAX_MESSAGE_CODE_POINTS,
    normalize_for_dedup,
    normalize_for_model,
)

# Duplicated constants from scripts.check_chat_leakage for validation
_VALID_LANGS = {"id", "en", "mixed"}
_VALID_SOURCES = {"synthetic", "hand_labeled"}
_REQUIRED_KEYS = ("id", "text", "intent", "lang", "source")


class DatasetError(ValueError):
    """Raised when dataset validation fails."""


class ModelLoadError(RuntimeError):
    """Raised when model artifact loading fails."""


def load_dataset(path: str | Path) -> list[dict]:
    """Load and validate a JSONL dataset file.

    Args:
        path: Path to the JSONL file.

    Returns:
        List of validated record dictionaries.

    Raises:
        DatasetError: If any validation fails. Messages contain file name,
            line number, and field name, but NEVER the record text.
    """
    path = Path(path)
    records: list[dict] = []
    seen_ids: set[str] = set()

    with path.open("r", encoding="utf-8") as handle:
        for line_no, raw_line in enumerate(handle, start=1):
            line = raw_line.rstrip("\n\r")

            if line.strip() == "":
                raise DatasetError(
                    f"{path.name}:{line_no} empty JSONL line"
                )

            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                raise DatasetError(
                    f"{path.name}:{line_no} invalid JSON"
                )

            if not isinstance(payload, dict):
                raise DatasetError(
                    f"{path.name}:{line_no} record must be a JSON object"
                )

            # Check keys exactly match required keys
            if set(payload.keys()) != set(_REQUIRED_KEYS):
                missing = set(_REQUIRED_KEYS) - set(payload.keys())
                extra = set(payload.keys()) - set(_REQUIRED_KEYS)
                parts = []
                if missing:
                    parts.append(f"missing fields {','.join(sorted(missing))}")
                if extra:
                    parts.append(f"extra fields {','.join(sorted(extra))}")
                raise DatasetError(
                    f"{path.name}:{line_no} " + "; ".join(parts)
                )

            # Validate all values are strings
            for key in _REQUIRED_KEYS:
                if not isinstance(payload[key], str):
                    raise DatasetError(
                        f"{path.name}:{line_no} field {key} must be a string"
                    )

            record_id = payload["id"]
            text = payload["text"]
            intent = payload["intent"]
            lang = payload["lang"]
            source = payload["source"]

            if record_id == "":
                raise DatasetError(
                    f"{path.name}:{line_no} id must be a non-empty string"
                )

            if record_id in seen_ids:
                raise DatasetError(
                    f"{path.name}:{line_no} duplicate id {record_id}"
                )
            seen_ids.add(record_id)

            if intent not in INTENTS:
                raise DatasetError(
                    f"{path.name}:{line_no} invalid intent"
                )

            if lang not in _VALID_LANGS:
                raise DatasetError(
                    f"{path.name}:{line_no} invalid lang"
                )

            if source not in _VALID_SOURCES:
                raise DatasetError(
                    f"{path.name}:{line_no} invalid source"
                )

            stripped = text.strip()
            length = len(stripped)
            if length < 1 or length > MAX_MESSAGE_CODE_POINTS:
                raise DatasetError(
                    f"{path.name}:{line_no} text length must be 1..{MAX_MESSAGE_CODE_POINTS}"
                )

            dedup_key = normalize_for_dedup(stripped)
            if not dedup_key:
                raise DatasetError(
                    f"{path.name}:{line_no} text has no alphanumeric content"
                )

            records.append(payload)

    return records


def dataset_fingerprint(records: Sequence[dict]) -> str:
    """Compute SHA-256 fingerprint of dataset records.

    Based on parsed records, so immune to CRLF/autocrlf differences.
    """
    lines = [
        json.dumps(rec, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        for rec in records
    ]
    content = "\n".join(lines).encode("utf-8")
    return hashlib.sha256(content).hexdigest()


def build_char_vectorizer() -> TfidfVectorizer:
    """Create a new character-level TF-IDF vectorizer.

    The text passed to it must ALWAYS be normalize_for_model(text) first.
    Kept compatible for leakage/near-duplicate checks.
    """
    return TfidfVectorizer(
        analyzer="char_wb",
        ngram_range=(2, 4),
        lowercase=False,
    )


def _build_char_vectorizer_modelk() -> TfidfVectorizer:
    return TfidfVectorizer(
        analyzer="char_wb",
        ngram_range=(2, 4),
        sublinear_tf=True,
        lowercase=True,
    )


def _build_word_vectorizer_modelk() -> TfidfVectorizer:
    return TfidfVectorizer(
        analyzer="word",
        ngram_range=(1, 2),
        sublinear_tf=True,
        token_pattern=r"(?u)\b\w+\b",
        lowercase=True,
    )


def _compute_class_weights(labels: Sequence[str]) -> dict[str, float]:
    counts = Counter(labels)
    n = len(labels)
    k = len(INTENTS)
    return {
        intent: (n / (k * counts[intent])) ** 0.5
        for intent in INTENTS
    }


def prepare_text(text: str) -> str:
    """Prepare text for the model: apply normalize_for_model."""
    return normalize_for_model(text)


@dataclass(frozen=True)
class TrainConfig:
    """Fixed Model K training configuration (no grid search, no CV)."""

    seed: int = 42
    c: float = 10.0
    max_iter: int = 3000

    def config_dict(self) -> dict:
        """Return JSON-serializable config dict describing exact Model K config."""
        return {
            "seed": self.seed,
            "c": self.c,
            "max_iter": self.max_iter,
            "solver": "lbfgs",
            "features": {
                "char_wb": {
                    "analyzer": "char_wb",
                    "ngram_range": [2, 4],
                    "sublinear_tf": True,
                },
                "word": {
                    "analyzer": "word",
                    "ngram_range": [1, 2],
                    "sublinear_tf": True,
                    "token_pattern": r"(?u)\b\w+\b",
                },
            },
            "class_weight": "(N/(K*count_c))**0.5",
            "cv": "none",
            "scoring": "fixed",
        }


DEFAULT_CONFIG = TrainConfig()


@dataclass(frozen=True)
class TrainResult:
    """Result of training.

    cv_best_params and cv_best_score are retained for API compatibility but are
    fixed values (no cross-validation or hyperparameter search is performed):
    cv_best_params is fixed to {"clf__C": 10.0} and cv_best_score is None.
    """

    pipeline: object
    model_version: str
    train_fingerprint: str
    cv_best_params: dict
    cv_best_score: float | None
    config: TrainConfig


def compute_model_version(train_records: Sequence[dict], config: TrainConfig) -> str:
    """Compute deterministic model version string."""
    train_fp = dataset_fingerprint(train_records)
    config_json = json.dumps(config.config_dict(), sort_keys=True, separators=(",", ":"))
    combined = train_fp + "\n" + config_json
    hash_suffix = hashlib.sha256(combined.encode("utf-8")).hexdigest()[:16]
    return f"chat-modelk-lr-{hash_suffix}"


def train(
    train_records: Sequence[dict],
    config: TrainConfig = DEFAULT_CONFIG,
) -> TrainResult:
    """Train the chat intent classifier (Model K, fixed configuration).

    Does NOT accept validation data. Validation is never used to fit, tune,
    or select the model. No GridSearchCV is performed.

    Args:
        train_records: Training records from load_dataset.
        config: Training configuration.

    Returns:
        TrainResult with fitted pipeline and metadata.

    Raises:
        DatasetError: If any intent has no records.
    """
    intent_counts = Counter(r["intent"] for r in train_records)
    for intent in INTENTS:
        if intent_counts.get(intent, 0) < 1:
            raise DatasetError(
                f"Intent {intent} has {intent_counts.get(intent, 0)} records, "
                "need at least 1"
            )

    texts = [prepare_text(r["text"]) for r in train_records]
    labels = [r["intent"] for r in train_records]
    class_weight = _compute_class_weights(labels)

    pipeline = Pipeline([
        ("f", FeatureUnion([
            ("c", _build_char_vectorizer_modelk()),
            ("w", _build_word_vectorizer_modelk()),
        ])),
        ("clf", LogisticRegression(
            C=config.c,
            max_iter=config.max_iter,
            solver="lbfgs",
            class_weight=class_weight,
            random_state=config.seed,
        )),
    ])

    with threadpool_limits(limits=1):
        pipeline.fit(texts, labels)

    model_version = compute_model_version(train_records, config)
    train_fp = dataset_fingerprint(train_records)

    return TrainResult(
        pipeline=pipeline,
        model_version=model_version,
        train_fingerprint=train_fp,
        cv_best_params={"clf__C": 10.0},
        cv_best_score=None,
        config=config,
    )


def save_artifacts(
    result: TrainResult,
    val_fingerprint: str,
    out_dir: str | Path,
) -> tuple[str, str]:
    """Save training artifacts to directory.

    Args:
        result: TrainResult from train().
        val_fingerprint: Fingerprint of validation dataset.
        out_dir: Output directory path.

    Returns:
        Tuple of (joblib_path, meta_path).
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    joblib_path = out_dir / "chat_classifier.joblib"
    meta_path = out_dir / "chat_classifier.meta.json"

    joblib.dump(result.pipeline, joblib_path)

    # Compute artifact SHA256
    with joblib_path.open("rb") as f:
        artifact_bytes = f.read()
    artifact_sha256 = hashlib.sha256(artifact_bytes).hexdigest()

    meta = {
        "modelVersion": result.model_version,
        "intents": list(INTENTS),
        "class_order": [str(name) for name in result.pipeline.classes_],
        "train_fingerprint": result.train_fingerprint,
        "val_fingerprint": val_fingerprint,
        "artifact_sha256": artifact_sha256,
        "config": result.config.config_dict(),
        "cv_best_params": result.cv_best_params,
        "cv_best_score": result.cv_best_score,
        "library_versions": {
            "numpy": np.__version__,
            "scipy": scipy.__version__,
            "scikit-learn": sklearn.__version__,
            "joblib": joblib.__version__,
            "threadpoolctl": threadpoolctl.__version__,
            "python": sys.version.split()[0],
        },
        "trained_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }

    with meta_path.open("w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")

    return str(joblib_path), str(meta_path)


@dataclass(frozen=True)
class LoadedModel:
    """Loaded model with metadata."""

    pipeline: object
    meta: dict
    model_version: str


def load_model(out_dir: str | Path) -> LoadedModel:
    """Load model artifacts from directory.

    Args:
        out_dir: Directory containing chat_classifier.joblib and .meta.json.

    Returns:
        LoadedModel with pipeline and metadata.

    Raises:
        ModelLoadError: If files missing, versions mismatch, or sha256 mismatch.
    """
    out_dir = Path(out_dir)
    joblib_path = out_dir / "chat_classifier.joblib"
    meta_path = out_dir / "chat_classifier.meta.json"

    if not joblib_path.exists():
        raise ModelLoadError(f"Missing joblib file: {joblib_path}")
    if not meta_path.exists():
        raise ModelLoadError(f"Missing meta file: {meta_path}")

    with meta_path.open("r", encoding="utf-8") as f:
        meta = json.load(f)

    required_meta_keys = {
        "modelVersion", "intents", "class_order", "train_fingerprint", "val_fingerprint",
        "artifact_sha256", "config", "cv_best_params", "cv_best_score",
        "library_versions", "trained_at",
    }
    missing_keys = required_meta_keys - set(meta.keys())
    if missing_keys:
        raise ModelLoadError(f"Missing meta keys: {missing_keys}")

    current_versions = {
        "numpy": np.__version__,
        "scipy": scipy.__version__,
        "scikit-learn": sklearn.__version__,
        "joblib": joblib.__version__,
        "threadpoolctl": threadpoolctl.__version__,
        "python": sys.version.split()[0],
    }

    library_versions = meta["library_versions"]
    if not isinstance(library_versions, dict):
        raise ModelLoadError("library_versions must be a JSON object")
    missing_libs = set(current_versions) - set(library_versions)
    if missing_libs:
        raise ModelLoadError(f"Missing library versions: {sorted(missing_libs)}")

    # Check library versions
    for lib in sorted(current_versions):
        expected_version = library_versions[lib]
        if current_versions[lib] != expected_version:
            raise ModelLoadError(
                f"Library version mismatch for {lib}: "
                f"expected {expected_version}, got {current_versions[lib]}"
            )

    if meta["intents"] != list(INTENTS):
        raise ModelLoadError(
            f"Metadata intents mismatch: expected {list(INTENTS)}, got {meta['intents']}"
        )

    # Check artifact SHA256
    with joblib_path.open("rb") as f:
        artifact_bytes = f.read()
    actual_sha256 = hashlib.sha256(artifact_bytes).hexdigest()
    if actual_sha256 != meta["artifact_sha256"]:
        raise ModelLoadError(
            f"Artifact SHA256 mismatch: expected {meta['artifact_sha256']}, "
            f"got {actual_sha256}"
        )

    pipeline = joblib.load(joblib_path)

    actual_class_order = [str(name) for name in pipeline.classes_]
    if set(actual_class_order) != set(INTENTS):
        raise ModelLoadError(
            f"Pipeline classes mismatch: expected {set(INTENTS)}, "
            f"got {set(actual_class_order)}"
        )
    if actual_class_order != meta["class_order"]:
        raise ModelLoadError(
            "Pipeline class order mismatch: "
            f"expected {meta['class_order']}, got {actual_class_order}"
        )

    return LoadedModel(
        pipeline=pipeline,
        meta=meta,
        model_version=meta["modelVersion"],
    )


@dataclass(frozen=True)
class Prediction:
    """Single prediction result."""

    intent: str
    confidence: float
    model_version: str


def predict(model: LoadedModel, text: str) -> Prediction:
    """Predict intent for a single text."""
    return predict_batch(model, [text])[0]


def predict_batch(model: LoadedModel, texts: Sequence[str]) -> list[Prediction]:
    """Predict intents for a batch of texts."""
    prepared = [prepare_text(t) for t in texts]
    proba = model.pipeline.predict_proba(prepared)
    classes = model.pipeline.classes_

    predictions = []
    for row in proba:
        idx = int(np.argmax(row))
        intent = classes[idx]
        confidence = float(row[idx])
        predictions.append(Prediction(
            intent=intent,
            confidence=confidence,
            model_version=model.model_version,
        ))
    return predictions


PROBE_TEXTS = (
    "jam buka hari sabtu berapa",
    "is there a dress code here",
    "ada promo malam ini",
    "bisa simpan botol di sini",
    "tolong carikan hotel murah",
    "what time does it close on weekdays",
)
