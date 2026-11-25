"""Container smoke test executed inside the running ml-service container."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE_URL = "http://127.0.0.1:8000"
INTENTS = {
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
}

_FAILED = False


def _record(name: str, ok: bool, detail: str = "") -> None:
    global _FAILED
    if ok:
        print(f"PASS {name}")
    else:
        _FAILED = True
        print(f"FAIL {name}: {detail}")


def _request(
    method: str,
    path: str,
    body: dict | None = None,
    token: str | None = None,
) -> tuple[int, dict]:
    headers: dict[str, str] = {}
    data: bytes | None = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    if token is not None:
        headers["X-Internal-Token"] = token
    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return exc.code, parsed


def _short_forecast_payload() -> dict:
    base = date(2024, 1, 1)
    return {
        "ingredientId": 1,
        "unit": "ML",
        "horizonDays": 7,
        "series": [
            {"businessDate": str(base + timedelta(days=i)), "quantity": 1.0}
            for i in range(5)
        ],
    }


def main() -> int:
    token = os.environ["ML_INTERNAL_TOKEN"]
    mode = os.environ.get("SMOKE_MODE", "ok")

    status_code, _ = _request("GET", "/health")
    _record("health_200", status_code == 200, f"got {status_code}")

    if mode == "ok":
        expected_version = os.environ["EXPECTED_MODEL_VERSION"]
        status_code, body = _request(
            "POST",
            "/ml/chat/classify",
            body={"message": "jam buka hari sabtu berapa"},
            token=token,
        )
        ok_200 = (
            status_code == 200
            and body.get("intent") in INTENTS
            and isinstance(body.get("confidence"), (int, float))
            and 0.0 <= float(body["confidence"]) <= 1.0
            and body.get("modelVersion") == expected_version
            and isinstance(body.get("requiresLlm"), bool)
        )
        _record("classify_ok_200", ok_200, f"status={status_code} body={body}")

        status_code, _ = _request(
            "POST",
            "/ml/chat/classify",
            body={"message": "jam buka hari sabtu berapa"},
            token=None,
        )
        _record("classify_no_token_401", status_code == 401, f"got {status_code}")

        status_code, _ = _request(
            "POST",
            "/ml/chat/classify",
            body={"message": "   "},
            token=token,
        )
        _record("classify_blank_422", status_code == 422, f"got {status_code}")

        status_code, _ = _request(
            "POST",
            "/ml/forecast/inventory",
            body=_short_forecast_payload(),
            token=token,
        )
        _record("forecast_short_422", status_code == 422, f"got {status_code}")

    elif mode == "missing":
        status_code, body = _request(
            "POST",
            "/ml/chat/classify",
            body={"message": "jam buka hari sabtu berapa"},
            token=token,
        )
        _record(
            "classify_missing_503",
            status_code == 503 and body.get("code") == "MODEL_UNAVAILABLE",
            f"status={status_code} body={body}",
        )

        status_code, _ = _request(
            "POST",
            "/ml/forecast/inventory",
            body=_short_forecast_payload(),
            token=token,
        )
        _record("forecast_short_422", status_code == 422, f"got {status_code}")

    else:
        _record("smoke_mode", False, f"unknown SMOKE_MODE={mode}")

    return 1 if _FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
