"""Tests for POST /ml/chat/classify (Round C)."""

from __future__ import annotations

import json
import math
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.chat_text import INTENTS, MAX_MESSAGE_CODE_POINTS
from app.main import app

TOKEN = "test-internal-token-secret"
FROZEN_VERSION = "chat-modelk-lr-d98a37b805f0f770"
MODEL_DIR = Path("models/chat_modelk_spec")


def make_fake_model(intent: str = "OPENING_HOURS", prob: float = 0.95) -> MagicMock:
    classes = list(INTENTS)
    row = np.zeros(len(classes))
    row[classes.index(intent)] = prob
    pipeline = MagicMock()
    pipeline.classes_ = classes
    pipeline.predict_proba.return_value = np.array([row])
    model = MagicMock()
    model.pipeline = pipeline
    model.model_version = FROZEN_VERSION
    return model


@pytest.fixture
def client(monkeypatch) -> TestClient:
    monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
    with patch("app.chat_classify.load_model", return_value=make_fake_model()):
        with TestClient(app) as c:
            yield c


def post_chat(client: TestClient, body: object, token: str | None = TOKEN):
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["X-Internal-Token"] = token
    return client.post("/ml/chat/classify", content=json.dumps(body), headers=headers)


def forecast_payload() -> dict:
    base = date(2024, 1, 1)
    return {
        "ingredientId": 1,
        "unit": "ML",
        "horizonDays": 7,
        "series": [
            {"businessDate": str(base + timedelta(days=i)), "quantity": float(i % 7 + 1)}
            for i in range(35)
        ],
    }


class TestAuth:
    def test_missing_token_returns_401(self, client: TestClient) -> None:
        resp = client.post("/ml/chat/classify", json={"message": "hello world"})
        assert resp.status_code == 401

    def test_wrong_token_returns_401(self, client: TestClient) -> None:
        resp = post_chat(client, {"message": "hello world"}, token="wrong")
        assert resp.status_code == 401

    def test_correct_token_returns_200(self, client: TestClient) -> None:
        resp = post_chat(client, {"message": "hello world"})
        assert resp.status_code == 200


class TestInputValidation:
    def test_missing_message_field_422(self, client: TestClient) -> None:
        assert post_chat(client, {}).status_code == 422

    def test_message_wrong_type_422(self, client: TestClient) -> None:
        assert post_chat(client, {"message": 12345}).status_code == 422

    def test_empty_message_422(self, client: TestClient) -> None:
        assert post_chat(client, {"message": ""}).status_code == 422

    def test_whitespace_only_422(self, client: TestClient) -> None:
        assert post_chat(client, {"message": "   "}).status_code == 422

    def test_message_too_long_422(self, client: TestClient) -> None:
        resp = post_chat(client, {"message": "a" * (MAX_MESSAGE_CODE_POINTS + 1)})
        assert resp.status_code == 422

    def test_message_at_max_length_ok(self, client: TestClient) -> None:
        resp = post_chat(client, {"message": "a" * MAX_MESSAGE_CODE_POINTS})
        assert resp.status_code == 200


class TestSuccessResponse:
    def test_response_fields_and_model_version(self, client: TestClient) -> None:
        body = post_chat(client, {"message": "jam buka berapa"}).json()
        assert {"intent", "confidence", "requiresLlm", "modelVersion"} <= body.keys()
        assert body["modelVersion"] == FROZEN_VERSION
        assert body["intent"] in INTENTS
        assert 0.0 <= body["confidence"] <= 1.0
        assert body["requiresLlm"] is False

    def test_requires_llm_true_only_for_advanced(self, monkeypatch) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        with patch(
            "app.chat_classify.load_model",
            return_value=make_fake_model("ADVANCED_CONVERSATION", 0.9),
        ):
            with TestClient(app) as c:
                resp = post_chat(c, {"message": "ceritain sesuatu"})
        assert resp.status_code == 200
        assert resp.json()["requiresLlm"] is True

    @pytest.mark.parametrize("intent", list(INTENTS))
    def test_all_11_intents_accepted(self, monkeypatch, intent: str) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        with patch(
            "app.chat_classify.load_model", return_value=make_fake_model(intent, 0.9)
        ):
            with TestClient(app) as c:
                resp = post_chat(c, {"message": "test message valid"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["intent"] == intent
        assert body["requiresLlm"] is (intent == "ADVANCED_CONVERSATION")
        assert math.isfinite(body["confidence"])
        assert 0.0 <= body["confidence"] <= 1.0
        assert body["modelVersion"] == FROZEN_VERSION


class TestModelLifecycle:
    def test_startup_success_loads_model(self, monkeypatch) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        fake = make_fake_model()
        with patch("app.chat_classify.load_model", return_value=fake) as loader:
            with TestClient(app) as c:
                assert c.get("/health").status_code == 200
                resp = post_chat(c, {"message": "jam buka berapa"})
        assert loader.call_count == 1
        assert resp.status_code == 200
        assert resp.json()["modelVersion"] == FROZEN_VERSION

    def test_model_loaded_once_per_app_startup_not_per_request(
        self, monkeypatch
    ) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        loader = MagicMock(return_value=make_fake_model())
        with patch("app.chat_classify.load_model", loader):
            with TestClient(app) as c:
                for i in range(3):
                    resp = post_chat(c, {"message": f"request {i}"})
                    assert resp.status_code == 200
        assert loader.call_count == 1

    def test_startup_failure_load_model_raising(self, monkeypatch) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        with patch("app.chat_classify.load_model", side_effect=RuntimeError("boom")):
            with TestClient(app) as c:
                assert c.get("/health").status_code == 200
                chat = post_chat(c, {"message": "hello"})
                assert chat.status_code == 503
                assert chat.json()["code"] == "MODEL_UNAVAILABLE"
                forecast = c.post(
                    "/ml/forecast/inventory",
                    json=forecast_payload(),
                    headers={"X-Internal-Token": TOKEN},
                )
                assert forecast.status_code == 200

    def test_startup_failure_missing_directory(self, monkeypatch, tmp_path: Path) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        monkeypatch.setenv("CHAT_MODEL_DIR", str(tmp_path / "missing"))
        with TestClient(app) as c:
            assert c.get("/health").status_code == 200
            chat = post_chat(c, {"message": "hello"})
            assert chat.status_code == 503
            assert chat.json()["code"] == "MODEL_UNAVAILABLE"
            forecast = c.post(
                "/ml/forecast/inventory",
                json=forecast_payload(),
                headers={"X-Internal-Token": TOKEN},
            )
            assert forecast.status_code == 200

    def test_startup_failure_corrupt_file(self, monkeypatch, tmp_path: Path) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        model_dir = tmp_path / "corrupt_model"
        model_dir.mkdir()
        (model_dir / "chat_classifier.joblib").write_bytes(b"not a joblib")
        (model_dir / "chat_classifier.meta.json").write_text("{}", encoding="utf-8")
        monkeypatch.setenv("CHAT_MODEL_DIR", str(model_dir))
        with TestClient(app) as c:
            assert c.get("/health").status_code == 200
            chat = post_chat(c, {"message": "hello"})
            assert chat.status_code == 503
            assert chat.json()["code"] == "MODEL_UNAVAILABLE"
            forecast = c.post(
                "/ml/forecast/inventory",
                json=forecast_payload(),
                headers={"X-Internal-Token": TOKEN},
            )
            assert forecast.status_code == 200


class TestNoRawTextInLogs:
    def test_raw_message_absent_from_logs(
        self, client: TestClient, caplog: pytest.LogCaptureFixture
    ) -> None:
        secret = "SUPERSECRET_MSG_DO_NOT_LOG_XYZ"
        with caplog.at_level("DEBUG"):
            post_chat(client, {"message": secret})
        assert secret not in caplog.text


class TestOpenApi:
    def test_openapi_contract(self) -> None:
        spec = app.openapi()
        path = spec["paths"]["/ml/chat/classify"]["post"]
        request_schema = path["requestBody"]["content"]["application/json"]["schema"]
        if "$ref" in request_schema:
            request_schema = spec["components"]["schemas"][
                request_schema["$ref"].rsplit("/", 1)[-1]
            ]
        props = request_schema["properties"]
        assert "message" in props
        assert "text" not in props
        assert "example" in props["message"] or "examples" in props["message"]

        responses = path["responses"]
        for code in ("200", "401", "422", "503"):
            assert code in responses

        response_schema = responses["200"]["content"]["application/json"]["schema"]
        if "$ref" in response_schema:
            response_schema = spec["components"]["schemas"][
                response_schema["$ref"].rsplit("/", 1)[-1]
            ]
        resp_props = response_schema["properties"]
        for field in ("confidence", "requiresLlm", "modelVersion", "intent"):
            assert field in resp_props

        intent_schema = resp_props["intent"]
        if "$ref" in intent_schema:
            intent_schema = spec["components"]["schemas"][
                intent_schema["$ref"].rsplit("/", 1)[-1]
            ]
        assert set(intent_schema.get("enum", [])) == set(INTENTS)
        assert "example" in response_schema or "examples" in response_schema


@pytest.mark.skipif(not MODEL_DIR.exists(), reason="models/chat_modelk_spec not present")
class TestRealArtifactSmoke:
    def test_real_artifact_loads_and_classifies(self, monkeypatch) -> None:
        monkeypatch.setenv("ML_INTERNAL_TOKEN", TOKEN)
        monkeypatch.delenv("CHAT_MODEL_DIR", raising=False)
        with TestClient(app) as c:
            resp = post_chat(c, {"message": "jam buka berapa"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["modelVersion"] == FROZEN_VERSION
        assert body["intent"] in INTENTS
        assert 0.0 <= body["confidence"] <= 1.0
