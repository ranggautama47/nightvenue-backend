"""Chat classification endpoint.

Model loaded once at startup via FastAPI lifespan (app.state.chat_model).
Missing/corrupt model -> app starts, chat returns 503 MODEL_UNAVAILABLE.
"""

from __future__ import annotations

import logging
import os
from typing import Literal

from fastapi import APIRouter, Depends, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.chat_text import INTENTS, MAX_MESSAGE_CODE_POINTS
from app.chat_training import LoadedModel, load_model, predict_batch
from app.security import verify_internal_token

logger = logging.getLogger(__name__)

router = APIRouter()

DEFAULT_CHAT_MODEL_DIR = "models/chat_modelk_spec"
MODEL_UNAVAILABLE = {"code": "MODEL_UNAVAILABLE", "message": "Chat model is unavailable"}

IntentName = Literal[
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
]


def load_chat_model(app) -> None:
    """Load chat model into app.state.chat_model. Never raises."""
    model_dir = os.environ.get("CHAT_MODEL_DIR", DEFAULT_CHAT_MODEL_DIR)
    try:
        app.state.chat_model = load_model(model_dir)
    except Exception as exc:  # noqa: BLE001 - missing/corrupt/version mismatch/any
        logger.warning(
            "Chat model unavailable: %s (dir=%s)", type(exc).__name__, model_dir
        )
        app.state.chat_model = None


def get_chat_model(http_request: Request) -> LoadedModel | None:
    return getattr(http_request.app.state, "chat_model", None)


class ChatClassifyRequest(BaseModel):
    message: str = Field(
        ...,
        description="User message to classify (1..500 codepoints)",
        examples=["jam buka berapa"],
    )

    @field_validator("message")
    @classmethod
    def _strip_and_validate(cls, value: str) -> str:
        stripped = value.strip()
        if len(stripped) == 0:
            raise ValueError("message must not be empty or whitespace-only")
        if len(stripped) > MAX_MESSAGE_CODE_POINTS:
            raise ValueError(
                f"message exceeds maximum length ({MAX_MESSAGE_CODE_POINTS} code points)"
            )
        return stripped


class ChatClassifyResponse(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "intent": "OPENING_HOURS",
                "confidence": 0.95,
                "requiresLlm": False,
                "modelVersion": "chat-modelk-lr-d98a37b805f0f770",
            }
        }
    )

    intent: IntentName = Field(..., description="One of the 11 supported intents")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence score")
    requiresLlm: bool = Field(
        ..., description="True when intent is ADVANCED_CONVERSATION"
    )
    modelVersion: str = Field(..., description="Loaded model version identifier")


@router.post(
    "/ml/chat/classify",
    response_model=ChatClassifyResponse,
    responses={
        200: {"description": "Successful classification"},
        401: {"description": "Unauthorized"},
        422: {"description": "Unprocessable Entity"},
        503: {"description": "Chat model unavailable"},
        500: {"description": "Internal server error"},
    },
    dependencies=[Depends(verify_internal_token)],
)
def classify(
    payload: ChatClassifyRequest, http_request: Request
) -> ChatClassifyResponse | JSONResponse:
    model = get_chat_model(http_request)
    if model is None:
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=MODEL_UNAVAILABLE,
        )

    try:
        prediction = predict_batch(model, [payload.message])[0]
    except Exception:  # noqa: BLE001
        logger.exception("Chat classification failed")
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"code": "INTERNAL_ERROR", "message": "Classification failed"},
        )

    if prediction.intent not in INTENTS:
        logger.error("Chat classification returned unexpected intent")
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"code": "INTERNAL_ERROR", "message": "Classification failed"},
        )

    return ChatClassifyResponse(
        intent=prediction.intent,
        confidence=prediction.confidence,
        requiresLlm=(prediction.intent == "ADVANCED_CONVERSATION"),
        modelVersion=prediction.model_version,
    )
