# NightVenue ML Service

Internal FastAPI service for inventory forecasting (Phase 9) and chat intent classification (Phase 10).

## Runtime & Setup

- **Python:** `3.12.9` (pinned in Docker and CI)
- Install pinned dependencies from `ml-service/`:

```bash
pip install -r requirements.txt -r requirements-dev.txt
```

## Lint & Tests

Run from `ml-service/`:

```bash
ruff check .
pytest tests/ -q
```

## Chat Intent Model (Model K)

- **Canonical training input:** `data/chat/train_v2.jsonl` (887 records)
- **Output directory:** `models/chat_modelk_spec` (configured via `CHAT_MODEL_DIR`, resolved relative to the working directory)
- **Build command:**

```bash
python -m scripts.build_chat_model --out-dir models/chat_modelk_spec
```

- **Artifact policy:** Model binaries (`models/`) are generated during Docker image build and are never committed to Git.

## Docker Build & Run

Build from `ml-service/` (or repository root with `./ml-service` context):

```bash
docker build -t nightvenue-ml:local ./ml-service
docker run --rm -e ML_INTERNAL_TOKEN=replace-me nightvenue-ml:local
```

## Environment Variables

- `ML_INTERNAL_TOKEN`: Shared secret required in the `X-Internal-Token` header for `/ml/*` endpoints.
- `CHAT_MODEL_DIR`: Path to the Model K artifact directory (default: `models/chat_modelk_spec`).

## Endpoints

- `GET /health`: Liveness probe only (returns `{"status": "ok", "service": "ml-service"}`).
- `POST /ml/chat/classify`:
  - Request: `{"message": "<text>"}`
  - Response (`200`): `{"intent": "...", "confidence": 0.95, "requiresLlm": false, "modelVersion": "chat-modelk-lr-d98a37b805f0f770"}`
  - Errors: `401` (missing/invalid `X-Internal-Token`), `422` (invalid/empty/over-limit message), `503` (`MODEL_UNAVAILABLE` if Model K is missing/unloadable).
- `POST /ml/forecast/inventory`: Inventory demand forecasting (`X-Internal-Token` required; returns `422` on insufficient/invalid series).
- **OpenAPI:** `/openapi.json` and `/docs`

## CI Gates & Dataset Policy

- CI runs `ruff check .`, `pytest tests/ -q`, exact leakage check (`scripts.check_chat_leakage`), near-duplicate gate (`scripts.ci_near_dup_gate`), Model K build + B2 evaluator reproducibility check, Docker build, and container smoke tests.
- **Documented near-duplicate exception:** `tr-0723` / `va-0861` (`OUT_OF_SCOPE`, cosine `0.9059`).
- **Held-out set:** The held-out evaluation set is an independent Phase 10.6 gate and is not part of this repository yet.
