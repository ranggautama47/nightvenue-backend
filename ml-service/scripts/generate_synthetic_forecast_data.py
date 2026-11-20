"""Deterministic synthetic inventory data for offline forecast evaluation."""

import hashlib
import json
from datetime import date, timedelta

import numpy as np

SEED = 20260101
TOTAL_DAYS = 126
START_DATE = date(2026, 1, 1)
PROFILES = {
    "A": {
        "unit": "ML",
        "level": 1000.0,
        "weekday_coef": (-120.0, -120.0, -120.0, -120.0, 120.0, 240.0, 120.0),
        "slope": 2.0,
        "noise_sd": 40.0,
        "zero_day_probability": 0.0,
    },
    "B": {
        "unit": "GRAM",
        "level": 500.0,
        "weekday_coef": (-20.0, -20.0, -20.0, -20.0, 20.0, 40.0, 20.0),
        "slope": 0.0,
        "noise_sd": 60.0,
        "zero_day_probability": 0.0,
    },
    "C": {
        "unit": "PIECE",
        "level": 30.0,
        "weekday_coef": (-3.0, -3.0, -3.0, -3.0, 3.0, 6.0, 3.0),
        "slope": 0.1,
        "noise_sd": 3.0,
        "zero_day_probability": 0.03,
    },
}


def generate_profile(profile: dict, seed: int) -> dict:
    rng = np.random.default_rng(seed)
    rows = []
    for t in range(TOTAL_DAYS):
        business_date = START_DATE + timedelta(days=t)
        zero_day = bool(rng.random() < profile["zero_day_probability"])
        quantity = max(
            0.0,
            profile["level"]
            + profile["weekday_coef"][business_date.weekday()]
            + profile["slope"] * t
            + rng.normal(0.0, profile["noise_sd"]),
        )
        if zero_day:
            quantity = 0.0
        if profile["unit"] == "PIECE":
            quantity = int(round(quantity))
        rows.append(
            {
                "businessDate": business_date.isoformat(),
                "quantity": quantity,
                "isZeroDay": zero_day,
            }
        )
    return rows


def dataset_fingerprint(rows: dict) -> str:
    canonical = json.dumps(rows, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def generate_all() -> dict:
    data = {}
    for index, (name, profile) in enumerate(PROFILES.items()):
        rows = generate_profile(profile, SEED + index)
        data[name] = {
            "unit": profile["unit"],
            "planted": {
                "level": profile["level"],
                "weekday_coef": list(profile["weekday_coef"]),
                "slope": profile["slope"],
            },
            "rows": rows,
        }
    return {
        "profiles": data,
        "fingerprint": dataset_fingerprint(
            {name: item["rows"] for name, item in data.items()}
        ),
    }
