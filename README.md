# NightVenue Backend

Backend for **NightVenue**, a fictional nightlife venue operations platform (POS, inventory, payments, bottle keeping, cashier shifts, hybrid chat). Built as an engineering portfolio project. Not affiliated with any real brand.

## Status

| Area | State |
|---|---|
| Phase 1 foundation (skeleton, Docker Compose, CI) | Partial — version pins are recorded; Docker Compose startup, CI status, the hosting spike and the repository secret audit are NOT VERIFIED (see `docs/PHASES.md` Phase 1). |
| Phase 2 database, business date, demo seed | Implemented |
| Business features (orders, payments, inventory, ...) | Order open/add items, whole-order VOID, per-item SERVE implemented; payments implemented — M3-A payment core + GET Payment, M3-B Xendit Create, M4-A authenticated webhook + Status API verification + settlement, M4-B opt-in reconciliation (all implemented and tested locally); BR-05 minimum-spend guard is implemented for CASH, CARD_EDC, and QRIS and locally tested. Xendit provider validation remains pending. |
| Performance and ML metrics | Validation-set results for the chat classifier are documented under `ml-service/reports/`; they are not a blind test. No production or independent heldout metrics exist yet, and no headline numbers are published here. |
| Phase 10 — Hybrid chat classifier (ML service) | In progress: the Model K classifier and its internal FastAPI endpoint (`ml-service`) and the Spring chat API (`POST /api/v1/chat`) with the ADR-011 routing policy are implemented on feature branches and are awaiting PM acceptance. Optional BYOK LLM, telemetry and independent heldout acceptance are not done yet. See [`ml-service/README.md`](ml-service/README.md). |
| Hosting | Not decided (ADR-019 is *Proposed*) |

## Stack

Spring Boot (Java), FastAPI (Python), PostgreSQL, Redis, Docker Compose, GitHub Actions. Exact pinned versions are in [`CONTEXT.md`](CONTEXT.md).

## Services

| Service | Role | Host access |
|---|---|---|
| `api` | Spring Boot. System of record and all business logic | `localhost:8080` |
| `ml-service` | FastAPI. Inventory forecasting and chat intent classification (internal only); no LLM and no database access | **Not published** (internal Docker network only) |
| `postgres` | PostgreSQL | `127.0.0.1:5433` (dev only) |
| `redis` | Cache, rate limit, refresh tokens | `127.0.0.1:6379` (dev only) |

## Inventory forecasting

NightVenue forecasts ingredient demand from the inventory ledger through `POST /api/v1/forecasts`, available to ADMIN and MANAGER. The response shows the selected method and both regression and baseline backtest MAEs; each successful request adds a row to `stock_forecasts`. The ML service stays on the internal Docker network. Series gaps become zero and negative daily net values are clamped; later voids remain dated on the void day, and the evaluation uses synthetic data rather than production accuracy. The internal chat intent classifier endpoint (FR-ML-01) is implemented in `ml-service`; FR-ML-02 has validation-set evaluation results only, and the independent heldout evaluation remains pending (Phase 10.6). The Spring chat API (FR-CHAT-01..12) is not implemented yet. See [`ml-service/README.md`](ml-service/README.md) for classifier details, model build, Docker packaging and CI gates.

## Quick start (Docker)

Requires Docker Desktop (engine running).

1. Create your local `.env` from the template and fill every empty secret. Never commit `.env`.

   ```powershell
   Copy-Item .env.example .env

   # prints a random secret; run it once per empty value
   function New-Secret { $b = New-Object byte[] 32; $r = [Security.Cryptography.RandomNumberGenerator]::Create(); $r.GetBytes($b); [Convert]::ToBase64String($b) }
   New-Secret
   ```

2. Seed is disabled by default. To load synthetic demo data in a local development environment, set `NIGHTVENUE_SEED_ENABLED=true` in `.env`. Keep it `false` on public deployments. Start the stack and check that all four services are `healthy`:

   ```powershell
   docker compose up -d --build
   docker compose ps
   ```

   If a local database already contains users, the seeder does not overwrite them. Reset local database volumes with `docker compose down -v` before reseeding demo credentials.

3. Verify:

   ```powershell
   curl http://localhost:8080/actuator/health
   docker compose exec ml-service python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/health').read().decode())"
   ```

   `ml-service` is intentionally unreachable from the host (`http://localhost:8000` must fail).

Stop with `docker compose down`.

## Tests

Phase 5C supports whole-order VOID and per-item SERVE; item-level VOID is deferred. PARTIALLY_PAID orders have no cancellation path until refund support exists. Payment guard for PENDING/PAID transactions (BR-20): implemented in Phase 6 (VOID blocked when PENDING/PAID payments exist). Refund is deferred (separate PM-approved milestone required).

Java (needs a JDK from `CONTEXT.md` and a running Docker engine, because Testcontainers starts PostgreSQL and Redis):

```powershell
cd api
./mvnw clean test
```

Python:

```powershell
cd ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt -r requirements-dev.txt
ruff check .
pytest
```

## Run on the host without the api/ml containers

Start only the data services, then run each app locally. The apps read the same variables as `.env.example`.

```powershell
docker compose up -d postgres redis

cd api
./mvnw spring-boot:run

cd ../ml-service
.\.venv\Scripts\Activate.ps1
uvicorn app.main:app --port 8000
```

## Continuous integration

GitHub Actions in `.github/workflows/`: `ci-api`, `ci-ml`, `docker`, `secret-scan`.

## Documentation

Design documents live in [`docs/`](docs/README.md) and are the single source of truth (PRD, architecture, database, UML, decisions, phases). They are written in Indonesian.

## Known limitations

- The 90-day inventory consumption history is synthetic data generated for portfolio demonstration purposes. It is not real production data.
- Demo venues, menus, and customers are synthetic and are not real operational data.
- Live deployment is unverified.
- Xendit sandbox/provider validation is still pending (the M3-B smoke test is skipped by design). QRIS Create/response shape, expiry, Create idempotency, provider status mapping, and provider-side event semantics remain unverified; M3-B, M4-A and M4-B are implemented and tested locally against a stub, not the real provider.
- Reconciliation (M4-B) scheduler defaults to `enabled=false`; not enabled in production configuration.
- Refund lifecycle deferred; a separate PM-approved implementation milestone is required (not part of M4/M6).
- Shift submit, manager review, cash variance reconciliation, and background cutoff scheduler are implemented (Phase 8). The cutoff scheduler defaults to disabled.
- `provider_transaction_id` mapping to Xendit `payment_id` remains OPEN.
- BR-05 minimum spend is enforced for CASH, CARD_EDC, and QRIS through `PaymentService.checkMinimumSpend` and is covered by local integration tests. This does not indicate Xendit provider validation; sandbox validation remains pending. The guard rejects an exhausting payment below the order's minimum-spend snapshot before the provider call.
- Chat classifier metrics come from a validation set that was also used for model comparison and threshold selection; they are not a blind test.
- An independent heldout evaluation is a mandatory Phase 10.6 acceptance gate and does not exist yet.
- The Spring chat API (`POST /api/v1/chat`) exists on a feature branch and is awaiting PM acceptance; it answers from PostgreSQL only. No LLM/BYOK path exists yet, and the classifier endpoint is internal to `ml-service`.
- Model binaries are built inside the Docker image and are not committed to Git.
- One documented near-duplicate exception exists between training and validation data (see ADR-024).

### Bottle keeping

- The redeem QR token proves possession of the label; it is **not strong authentication** (ADR-020). Anyone holding the label who is confirmed by a cashier can redeem the bottle.
- Deposit requires an order linked to a customer through `order.customer_id`. Walk-in orders without a customer cannot deposit a bottle.
- The bottle expiry job is off by default (`nightvenue.bottle.expiry.enabled=false`) and is independent of payment reconciliation.
- Bottle retention is 30 days (`nightvenue.bottle.retention-days`); this property is validated at startup.

### Cashier shifts

- **Opening**: A user with role `CASHIER`, `MANAGER`, or `ADMIN` may have at most one `OPEN` shift globally across all venues (`uq_cashier_shifts_one_open_per_cashier`). Shifts are opened via `POST /api/v1/shifts`.
- **Cash payment gating**: Recording a `CASH` payment requires an active `OPEN` shift owned by the authenticated payment processor matching the venue and business date.
- **Reconciliation formulas**:
  - `expected_cash = opening_float + SUM(amount)` where `shift_id = <exact shift ID> AND method = 'CASH' AND status = 'PAID'`.
  - `variance = actual_cash - expected_cash`.
- **Submission**: The shift owner submits counted cash via `POST /api/v1/shifts/{id}/submit`. Submitting transitions the shift to `SUBMITTED`, records expected cash and variance, and prevents further cash payments on that shift. Only the shift owner can submit. Submission note is optional.
- **Manager review & notes**: Shifts are reviewed and closed via `POST /api/v1/shifts/{id}/review`. Allowed roles are `MANAGER` and `ADMIN`. Separation of duties is strictly enforced: a user cannot review their own shift (`reviewed_by != cashier_id`). A review note is required when `variance != 0` OR `forced_review_at` is set; otherwise it is optional.
- **Finality**: Transition to `RECONCILED` is final and cannot be reviewed again through the shift review workflow.
- **Stale shift transitions**:
  - Shifts left open past the venue's business day cutoff are transitioned to `MANAGER_REVIEW` (with `forced_review_at` set) along two request paths:
    1. `POST /api/v1/shifts` when opening a new shift.
    2. `POST /api/v1/shifts/{id}/submit` when submitting a stale shift (rejecting with 409 `SHIFT_NOT_OPEN`).
  - Shifts in `MANAGER_REVIEW` require manager input of `actual_cash` and a review note upon reconciliation.
- **Cutoff job configuration & concurrency**:
  - Application default: `nightvenue.shift.cutoff.enabled=false` (disabled by default). Fixed delay defaults to `PT1M` via `nightvenue.shift.cutoff.fixed-delay`.
  - Deployment configuration: An operator enabling the background cutoff scheduler sets `NIGHTVENUE_SHIFT_CUTOFF_ENABLED=true` in the target deployment environment.
  - Concurrent execution: The cutoff job supports concurrent execution across application instances. Conditional database updates ensure that each stale shift is transitioned only once, and the forced-review audit is written only by the execution that successfully updates the row. No leader election is required for this transition.
- **Audit trail**: Shift state transitions write append-only audit entries to `audit_logs`:
  - `SHIFT_OPENED`: records `business_date`.
  - `SHIFT_FORCED_REVIEW`: records `business_date` (actor is the shift owner when triggered via request path, or `NULL` when triggered by the system cutoff job).
  - `SHIFT_SUBMITTED`: records `expectedCash`, `actualCash`, and `variance`.
  - `SHIFT_RECONCILED`: records `source_status`, `expected_cash`, `actual_cash`, and `variance`.
  Cash counts and calculated variance are recorded in audit details for financial traceability; submission and review note texts are excluded.

### Demo accounts

All four demo accounts use password `password` (demo only; never use this password outside a local demo); roles are `ADMIN`, `MANAGER`, `CASHIER`, and `CUSTOMER` respectively. Demo seed must remain disabled on any public deployment.

- `admin@nightvenue.demo`
- `manager@nightvenue.demo`
- `cashier@nightvenue.demo`
- `customer@nightvenue.demo`

The Phase 3 API returns access tokens in the login/refresh response body; frontend memory-only storage is a frontend responsibility and is not verified here. Refresh tokens are sent only in the `HttpOnly` `nv_refresh` cookie.

### Authentication API

The four routes are `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, and `GET /api/v1/auth/me`. The contract, request/response schemas, and error responses are in [`api/src/main/resources/static/openapi.yaml`](api/src/main/resources/static/openapi.yaml). OpenAPI is private by default; set `NIGHTVENUE_OPENAPI_PUBLIC=true` only on non-production environments.

Refresh rotation is strict: reusing an old refresh token revokes all of that user's sessions. The frontend must use single-flight refresh handling; concurrent refresh requests with one cookie may log the user out everywhere.

### Reverse proxy configuration (trusted proxies)

By default, Tomcat trusts loopback proxies only (`127.0.0.0/8`, `::1`). Rate limiting derives client IPs via `request.getRemoteAddr()`. If deploying behind a real reverse proxy (e.g., NGINX, AWS ALB, Cloudflare, or Docker bridge), configure `APP_TRUSTED_PROXIES` in your environment with a regular expression matching your proxy's internal IP addresses or CIDR blocks (e.g. `10[.][0-9]+[.][0-9]+[.][0-9]+|172[.](1[6-9]|2[0-9]|3[01])[.][0-9]+[.][0-9]+`). Untrusted peers will have their `X-Forwarded-For` header safely ignored, preventing rate limit bypass.

## License

Not chosen yet.
