"""Tests for chat_training module."""

from __future__ import annotations

import inspect
import json
from collections.abc import Callable
from pathlib import Path

import pytest

from app.chat_text import INTENTS
from app.chat_training import (
    _VALID_LANGS,
    _VALID_SOURCES,
    PROBE_TEXTS,
    DatasetError,
    LoadedModel,
    ModelLoadError,
    Prediction,
    TrainConfig,
    build_char_vectorizer,
    compute_model_version,
    dataset_fingerprint,
    load_dataset,
    load_model,
    predict,
    predict_batch,
    prepare_text,
    save_artifacts,
    train,
)
from scripts.check_chat_leakage import VALID_LANGS, VALID_SOURCES


def _make_records() -> list[dict]:
    """Create a tiny inline corpus: 6 distinct texts per intent."""
    records = []
    for intent in INTENTS:
        for k in range(6):
            records.append({
                "id": f"{intent.lower()}-{k}",
                "text": f"{intent.lower()} contoh {k} kata{k}",
                "intent": intent,
                "lang": "en",
                "source": "synthetic",
            })
    return records


def _write_jsonl(path: Path, records: list[dict]) -> None:
    lines = [json.dumps(r, ensure_ascii=False) for r in records]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _train_and_save(tmp_path: Path) -> Path:
    records = _make_records()
    config = TrainConfig(c_grid=(1.0,), cv_folds=2)
    result = train(records, config)
    out_dir = tmp_path / "models"
    save_artifacts(result, dataset_fingerprint(records), out_dir)
    return out_dir


def _mutate_meta(out_dir: Path, mutate: Callable[[dict], None]) -> None:
    meta_path = out_dir / "chat_classifier.meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    mutate(meta)
    meta_path.write_text(
        json.dumps(meta, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


class TestLoadDataset:
    def test_valid_file_ok(self, tmp_path: Path) -> None:
        records = _make_records()
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        loaded = load_dataset(path)
        assert len(loaded) == len(records)

    def test_bad_json_raises(self, tmp_path: Path) -> None:
        path = tmp_path / "train.jsonl"
        path.write_text("{bad json}\n", encoding="utf-8")
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "invalid JSON" in str(exc.value)
        assert "bad json" not in str(exc.value).lower()

    def test_extra_key_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["extra"] = "field"
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "extra fields" in str(exc.value)

    def test_missing_key_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        del records[0]["intent"]
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "missing fields" in str(exc.value)

    def test_bad_intent_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["intent"] = "NOT_AN_INTENT"
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "invalid intent" in str(exc.value)

    def test_bad_lang_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["lang"] = "fr"
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "invalid lang" in str(exc.value)

    def test_bad_source_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["source"] = "other"
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "invalid source" in str(exc.value)

    def test_empty_text_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["text"] = "   "
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "text length must be 1" in str(exc.value)

    def test_text_too_long_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["text"] = "x" * 501
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "text length must be 1..500" in str(exc.value)

    def test_blank_line_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with path.open("a", encoding="utf-8") as f:
            f.write("\n")
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "empty JSONL line" in str(exc.value)

    def test_duplicate_id_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[1]["id"] = records[0]["id"]
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "duplicate id" in str(exc.value)

    def test_non_string_value_raises(self, tmp_path: Path) -> None:
        records = _make_records()
        records[0]["text"] = 123  # type: ignore
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert "field text must be a string" in str(exc.value)

    def test_error_message_never_contains_text(self, tmp_path: Path) -> None:
        records = _make_records()
        secret = "SECRET_TEXT_SHOULD_NOT_APPEAR"
        records[0]["text"] = secret
        records[0]["intent"] = "NOT_AN_INTENT"
        path = tmp_path / "train.jsonl"
        _write_jsonl(path, records)
        with pytest.raises(DatasetError) as exc:
            load_dataset(path)
        assert secret not in str(exc.value)


class TestTrainSaveLoadPredict:
    def test_train_save_load_predict(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        result = train(records, config)

        out_dir = tmp_path / "models"
        val_fp = dataset_fingerprint(records)
        joblib_path, meta_path = save_artifacts(result, val_fp, out_dir)

        assert Path(joblib_path).exists()
        assert Path(meta_path).exists()

        loaded = load_model(out_dir)
        assert isinstance(loaded, LoadedModel)
        assert loaded.model_version == result.model_version

        required_keys = {
            "modelVersion", "intents", "train_fingerprint", "val_fingerprint",
            "artifact_sha256", "config", "cv_best_params", "cv_best_score",
            "library_versions", "trained_at", "class_order",
        }
        assert set(loaded.meta.keys()) >= required_keys
        assert loaded.meta["class_order"] == sorted(INTENTS)

        # A training point must be recovered with its own label.
        pred = predict(loaded, "opening_hours contoh 0 kata0")
        assert isinstance(pred, Prediction)
        assert pred.intent == "OPENING_HOURS"
        assert 0.0 <= pred.confidence <= 1.0
        assert pred.model_version == result.model_version

        preds = predict_batch(
            loaded,
            ["menu_information contoh 0 kata0", "promotion contoh 0 kata0"],
        )
        assert [p.intent for p in preds] == ["MENU_INFORMATION", "PROMOTION"]
        for p in preds:
            assert 0.0 <= p.confidence <= 1.0

    def test_determinism(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)

        result1 = train(records, config)
        out_dir1 = tmp_path / "models1"
        save_artifacts(result1, dataset_fingerprint(records), out_dir1)
        loaded1 = load_model(out_dir1)

        result2 = train(records, config)
        out_dir2 = tmp_path / "models2"
        save_artifacts(result2, dataset_fingerprint(records), out_dir2)
        loaded2 = load_model(out_dir2)

        assert result1.model_version == result2.model_version

        preds1 = predict_batch(loaded1, PROBE_TEXTS)
        preds2 = predict_batch(loaded2, PROBE_TEXTS)
        for p1, p2 in zip(preds1, preds2):
            assert p1.intent == p2.intent
            assert abs(p1.confidence - p2.confidence) < 1e-9

    def test_model_version_changes_with_text(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        result1 = train(records, config)

        records2 = [dict(r) for r in records]
        records2[0]["text"] = "changed text"
        result2 = train(records2, config)

        assert result1.model_version != result2.model_version

    def test_model_version_changes_with_config(self, tmp_path: Path) -> None:
        records = _make_records()
        config1 = TrainConfig(c_grid=(1.0,), cv_folds=2)
        config2 = TrainConfig(c_grid=(2.0,), cv_folds=2)
        result1 = train(records, config1)
        result2 = train(records, config2)

        assert result1.model_version != result2.model_version

    def test_model_version_same_after_crlf_rewrite(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)

        path1 = tmp_path / "train1.jsonl"
        _write_jsonl(path1, records)
        loaded1 = load_dataset(path1)
        result1 = train(loaded1, config)

        path2 = tmp_path / "train2.jsonl"
        lines = [
            json.dumps(r, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
            for r in records
        ]
        path2.write_bytes(("\r\n".join(lines) + "\r\n").encode("utf-8"))
        loaded2 = load_dataset(path2)
        result2 = train(loaded2, config)

        assert result1.model_version == result2.model_version

    def test_load_model_raises_on_altered_artifact(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        result = train(records, config)
        out_dir = tmp_path / "models"
        save_artifacts(result, dataset_fingerprint(records), out_dir)

        joblib_path = out_dir / "chat_classifier.joblib"
        joblib_path.write_bytes(b"corrupted")

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "SHA256 mismatch" in str(exc.value) or "artifact" in str(exc.value).lower()

    def test_load_model_raises_on_missing_meta(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        result = train(records, config)
        out_dir = tmp_path / "models"
        save_artifacts(result, dataset_fingerprint(records), out_dir)

        (out_dir / "chat_classifier.meta.json").unlink()

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "Missing meta file" in str(exc.value)

    def test_load_model_raises_on_library_version_mismatch(self, tmp_path: Path) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        result = train(records, config)
        out_dir = tmp_path / "models"
        save_artifacts(result, dataset_fingerprint(records), out_dir)

        meta_path = out_dir / "chat_classifier.meta.json"
        with meta_path.open("r", encoding="utf-8") as f:
            meta = json.load(f)
        meta["library_versions"]["numpy"] = "999.999.999"
        with meta_path.open("w", encoding="utf-8") as f:
            json.dump(meta, f, indent=2, sort_keys=True, ensure_ascii=False)
            f.write("\n")

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "version mismatch" in str(exc.value).lower()

    def test_load_model_raises_on_missing_class_order(self, tmp_path: Path) -> None:
        out_dir = _train_and_save(tmp_path)

        def drop_class_order(meta: dict) -> None:
            meta.pop("class_order")

        _mutate_meta(out_dir, drop_class_order)

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "class_order" in str(exc.value)

    def test_load_model_raises_on_mismatched_class_order(self, tmp_path: Path) -> None:
        out_dir = _train_and_save(tmp_path)

        def reverse_class_order(meta: dict) -> None:
            meta["class_order"] = list(reversed(meta["class_order"]))

        _mutate_meta(out_dir, reverse_class_order)

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "class order mismatch" in str(exc.value).lower()

    def test_load_model_raises_on_mismatched_intents(self, tmp_path: Path) -> None:
        out_dir = _train_and_save(tmp_path)

        def reverse_intents(meta: dict) -> None:
            meta["intents"] = list(reversed(meta["intents"]))

        _mutate_meta(out_dir, reverse_intents)

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "intents mismatch" in str(exc.value).lower()

    def test_load_model_raises_on_missing_library_version(self, tmp_path: Path) -> None:
        out_dir = _train_and_save(tmp_path)

        def drop_joblib(meta: dict) -> None:
            meta["library_versions"].pop("joblib")

        _mutate_meta(out_dir, drop_joblib)

        with pytest.raises(ModelLoadError) as exc:
            load_model(out_dir)
        assert "missing library versions" in str(exc.value).lower()

    def test_train_signature_no_val_param(self) -> None:
        sig = inspect.signature(train)
        params = list(sig.parameters.keys())
        assert "val" not in params
        assert "validation" not in params

    def test_train_raises_on_insufficient_records_per_intent(self, tmp_path: Path) -> None:
        records = []
        for intent in INTENTS:
            records.append({
                "id": f"{intent.lower()}-0",
                "text": f"{intent.lower()} contoh",
                "intent": intent,
                "lang": "en",
                "source": "synthetic",
            })
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        with pytest.raises(DatasetError) as exc:
            train(records, config)
        assert "need at least 2" in str(exc.value)

    def test_duplicated_constants_match_checker(self) -> None:
        assert _VALID_LANGS == VALID_LANGS
        assert _VALID_SOURCES == VALID_SOURCES


class TestTrainCLI:
    def test_train_cli_on_tiny_corpus(self, tmp_path: Path) -> None:
        # Create completely distinct train and val records to avoid near-duplicates
        # Need at least 5 records per intent for cv_folds=5
        train_records = [
            # OPENING_HOURS (5)
            {
                "id": "tr-0001",
                "text": "jam buka hari ini berapa",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0002",
                "text": "kapan tempat ini tutup",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0003",
                "text": "buka dari jam berapa besok",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0004",
                "text": "operasional sampai jam berapa",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0005",
                "text": "hari minggu buka nggak",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
            # VENUE_AREA (5)
            {
                "id": "tr-0006",
                "text": "area rooftop di lantai berapa",
                "intent": "VENUE_AREA",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0007",
                "text": "zona vip ada di mana",
                "intent": "VENUE_AREA",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0008",
                "text": "ruang indoor ada nggak",
                "intent": "VENUE_AREA",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0009",
                "text": "area smoking di mana",
                "intent": "VENUE_AREA",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0010",
                "text": "meja dekat panggung ada",
                "intent": "VENUE_AREA",
                "lang": "id",
                "source": "synthetic",
            },
            # MENU_INFORMATION (5)
            {
                "id": "tr-0011",
                "text": "menu makanan apa saja",
                "intent": "MENU_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0012",
                "text": "minuman signature apa ada",
                "intent": "MENU_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0013",
                "text": "ada makanan vegetarian nggak",
                "intent": "MENU_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0014",
                "text": "harga botol whiskey berapa",
                "intent": "MENU_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0015",
                "text": "rekomendasi cocktail apa",
                "intent": "MENU_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            # BOTTLE_KEEPING (5)
            {
                "id": "tr-0016",
                "text": "bisa simpan botol di sini",
                "intent": "BOTTLE_KEEPING",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0017",
                "text": "lama simpan botol berapa hari",
                "intent": "BOTTLE_KEEPING",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0018",
                "text": "biaya bottle keeping berapa",
                "intent": "BOTTLE_KEEPING",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0019",
                "text": "botol dibuka bisa disimpan",
                "intent": "BOTTLE_KEEPING",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0020",
                "text": "cara klaim botol simpanan",
                "intent": "BOTTLE_KEEPING",
                "lang": "id",
                "source": "synthetic",
            },
            # MINIMUM_SPEND (5)
            {
                "id": "tr-0021",
                "text": "minimum spend meja vip berapa",
                "intent": "MINIMUM_SPEND",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0022",
                "text": "minimal belanja sofa area",
                "intent": "MINIMUM_SPEND",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0023",
                "text": "min spend meja biasa berapa",
                "intent": "MINIMUM_SPEND",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0024",
                "text": "minimum spend weekend beda",
                "intent": "MINIMUM_SPEND",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0025",
                "text": "apakah ada minimum spend",
                "intent": "MINIMUM_SPEND",
                "lang": "id",
                "source": "synthetic",
            },
            # PROMOTION (5)
            {
                "id": "tr-0026",
                "text": "promo apa yang lagi jalan",
                "intent": "PROMOTION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0027",
                "text": "ada happy hour malam ini",
                "intent": "PROMOTION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0028",
                "text": "promo ladies night berapa",
                "intent": "PROMOTION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0029",
                "text": "diskon member ada nggak",
                "intent": "PROMOTION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0030",
                "text": "voucher promo gimana cara",
                "intent": "PROMOTION",
                "lang": "id",
                "source": "synthetic",
            },
            # TABLE_AVAILABILITY (5)
            {
                "id": "tr-0031",
                "text": "meja kosong untuk 4 orang",
                "intent": "TABLE_AVAILABILITY",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0032",
                "text": "tersedia meja besar tidak",
                "intent": "TABLE_AVAILABILITY",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0033",
                "text": "meja dekat bar ada nggak",
                "intent": "TABLE_AVAILABILITY",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0034",
                "text": "booth untuk 6 orang kosong",
                "intent": "TABLE_AVAILABILITY",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0035",
                "text": "meja vip available hari ini",
                "intent": "TABLE_AVAILABILITY",
                "lang": "id",
                "source": "synthetic",
            },
            # RESERVATION (5)
            {
                "id": "tr-0036",
                "text": "cara reservasi meja gimana",
                "intent": "RESERVATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0037",
                "text": "butuh deposit untuk booking",
                "intent": "RESERVATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0038",
                "text": "bisa cancel reservasi nggak",
                "intent": "RESERVATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0039",
                "text": "reservasi lewat wa bisa",
                "intent": "RESERVATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0040",
                "text": "syarat booking meja apa",
                "intent": "RESERVATION",
                "lang": "id",
                "source": "synthetic",
            },
            # GENERAL_VENUE_INFORMATION (5)
            {
                "id": "tr-0041",
                "text": "alamat tempat ini di mana",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0042",
                "text": "parkir motor ada nggak",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0043",
                "text": "dress code nya apa",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0044",
                "text": "bisa bawa anak nggak",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0045",
                "text": "wifi password apa",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "id",
                "source": "synthetic",
            },
            # ADVANCED_CONVERSATION (5)
            {
                "id": "tr-0046",
                "text": "bandingkan promo dan meja kosong",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0047",
                "text": "rekomendasi area untuk ulang tahun",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0048",
                "text": "promo apa aja dan meja vip ada",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0049",
                "text": "buka jam berapa dan minimum spend",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0050",
                "text": "area vip promo happy hour detail",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "id",
                "source": "synthetic",
            },
            # OUT_OF_SCOPE (5)
            {
                "id": "tr-0051",
                "text": "pesan tiket bioskop dong",
                "intent": "OUT_OF_SCOPE",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0052",
                "text": "rekomendasi hotel dekat sini",
                "intent": "OUT_OF_SCOPE",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0053",
                "text": "cuaca besok gimana",
                "intent": "OUT_OF_SCOPE",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0054",
                "text": "harga emas hari ini berapa",
                "intent": "OUT_OF_SCOPE",
                "lang": "id",
                "source": "synthetic",
            },
            {
                "id": "tr-0055",
                "text": "cara masak nasi goreng",
                "intent": "OUT_OF_SCOPE",
                "lang": "id",
                "source": "synthetic",
            },
        ]
        val_records = [
            # OPENING_HOURS (2)
            {
                "id": "va-0001",
                "text": "what time do you open today",
                "intent": "OPENING_HOURS",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0002",
                "text": "when does this place close",
                "intent": "OPENING_HOURS",
                "lang": "en",
                "source": "synthetic",
            },
            # VENUE_AREA (2)
            {
                "id": "va-0003",
                "text": "where is the rooftop area located",
                "intent": "VENUE_AREA",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0004",
                "text": "which floor is the vip zone on",
                "intent": "VENUE_AREA",
                "lang": "en",
                "source": "synthetic",
            },
            # MENU_INFORMATION (2)
            {
                "id": "va-0005",
                "text": "what food items are on the menu",
                "intent": "MENU_INFORMATION",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0006",
                "text": "do you have signature drinks",
                "intent": "MENU_INFORMATION",
                "lang": "en",
                "source": "synthetic",
            },
            # BOTTLE_KEEPING (2)
            {
                "id": "va-0007",
                "text": "can I keep my bottle here",
                "intent": "BOTTLE_KEEPING",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0008",
                "text": "how long can I store a bottle",
                "intent": "BOTTLE_KEEPING",
                "lang": "en",
                "source": "synthetic",
            },
            # MINIMUM_SPEND (2)
            {
                "id": "va-0009",
                "text": "what is the minimum spend for vip",
                "intent": "MINIMUM_SPEND",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0010",
                "text": "minimum spend for sofa area please",
                "intent": "MINIMUM_SPEND",
                "lang": "en",
                "source": "synthetic",
            },
            # PROMOTION (2)
            {
                "id": "va-0011",
                "text": "what promotions are running now",
                "intent": "PROMOTION",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0012",
                "text": "is there a happy hour tonight",
                "intent": "PROMOTION",
                "lang": "en",
                "source": "synthetic",
            },
            # TABLE_AVAILABILITY (2)
            {
                "id": "va-0013",
                "text": "do you have a table for four available",
                "intent": "TABLE_AVAILABILITY",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0014",
                "text": "any large tables free right now",
                "intent": "TABLE_AVAILABILITY",
                "lang": "en",
                "source": "synthetic",
            },
            # RESERVATION (2)
            {
                "id": "va-0015",
                "text": "how do I reserve a table",
                "intent": "RESERVATION",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0016",
                "text": "is a deposit required for booking",
                "intent": "RESERVATION",
                "lang": "en",
                "source": "synthetic",
            },
            # GENERAL_VENUE_INFORMATION (2)
            {
                "id": "va-0017",
                "text": "where is this venue located",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0018",
                "text": "is there motorcycle parking available",
                "intent": "GENERAL_VENUE_INFORMATION",
                "lang": "en",
                "source": "synthetic",
            },
            # ADVANCED_CONVERSATION (2)
            {
                "id": "va-0019",
                "text": "compare promotions and table availability",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0020",
                "text": "recommend an area for a birthday party",
                "intent": "ADVANCED_CONVERSATION",
                "lang": "en",
                "source": "synthetic",
            },
            # OUT_OF_SCOPE (2)
            {
                "id": "va-0021",
                "text": "book me a movie ticket please",
                "intent": "OUT_OF_SCOPE",
                "lang": "en",
                "source": "synthetic",
            },
            {
                "id": "va-0022",
                "text": "find me a hotel nearby",
                "intent": "OUT_OF_SCOPE",
                "lang": "en",
                "source": "synthetic",
            },
        ]
        train_path = tmp_path / "train.jsonl"
        val_path = tmp_path / "val.jsonl"
        _write_jsonl(train_path, train_records)
        _write_jsonl(val_path, val_records)

        from scripts.train_chat_classifier import main
        code = main([
            "--train", str(train_path),
            "--val", str(val_path),
            "--out-dir", str(tmp_path / "models"),
        ])
        assert code == 0

    def test_train_cli_refuses_on_near_duplicate(self, tmp_path: Path) -> None:
        train_records = [
            {
                "id": "tr-0001",
                "text": "jam buka hari ini",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
        ]
        val_records = [
            {
                "id": "va-0001",
                "text": "jam buka hari ini ya",
                "intent": "OPENING_HOURS",
                "lang": "id",
                "source": "synthetic",
            },
        ]
        train_path = tmp_path / "train.jsonl"
        val_path = tmp_path / "val.jsonl"
        _write_jsonl(train_path, train_records)
        _write_jsonl(val_path, val_records)

        from scripts.train_chat_classifier import main
        code = main([
            "--train", str(train_path),
            "--val", str(val_path),
            "--out-dir", str(tmp_path / "models"),
        ])
        assert code == 1

    def test_train_cli_invalid_file_returns_two(self, tmp_path: Path) -> None:
        train_path = tmp_path / "train.jsonl"
        val_path = tmp_path / "val.jsonl"
        train_path.write_text("{bad json}\n", encoding="utf-8")
        val_records = _make_records()
        _write_jsonl(val_path, val_records)

        from scripts.train_chat_classifier import main
        code = main([
            "--train", str(train_path),
            "--val", str(val_path),
            "--out-dir", str(tmp_path / "models"),
        ])
        assert code == 2


class TestPrepareText:
    def test_prepare_text_uses_normalize_for_model(self) -> None:
        text = "  Jam 2.30,  meja #4?  "
        assert prepare_text(text) == "jam 2.30, meja #4?"


class TestDatasetFingerprint:
    def test_immune_to_crlf(self) -> None:
        records = _make_records()
        fp1 = dataset_fingerprint(records)

        lines = [
            json.dumps(r, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
            for r in records
        ]
        content_crlf = "\r\n".join(lines) + "\r\n"
        parsed = [json.loads(line) for line in content_crlf.strip().split("\r\n")]
        fp2 = dataset_fingerprint(parsed)

        assert fp1 == fp2


class TestBuildCharVectorizer:
    def test_returns_tfidf_vectorizer(self) -> None:
        vec = build_char_vectorizer()
        assert isinstance(vec, type(build_char_vectorizer()))
        assert vec.analyzer == "char_wb"
        assert vec.ngram_range == (2, 4)
        assert vec.lowercase is False


class TestComputeModelVersion:
    def test_format(self) -> None:
        records = _make_records()
        config = TrainConfig(c_grid=(1.0,), cv_folds=2)
        version = compute_model_version(records, config)
        assert version.startswith("chat-charwb-lr-")
        assert len(version) == len("chat-charwb-lr-") + 16


class TestTrainConfig:
    def test_config_dict(self) -> None:
        config = TrainConfig()
        d = config.config_dict()
        assert d["seed"] == 42
        assert d["c_grid"] == [0.5, 1.0, 2.0, 5.0]
        assert d["cv_folds"] == 5
        assert d["max_iter"] == 1000
        assert d["analyzer"] == "char_wb"
        assert d["ngram_range"] == [2, 4]
        assert d["solver"] == "lbfgs"
        assert d["scoring"] == "f1_macro"
