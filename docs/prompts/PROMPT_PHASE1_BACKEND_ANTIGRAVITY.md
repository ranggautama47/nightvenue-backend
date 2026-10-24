# NightVenue — Phase 1 Backend Foundation: Execution Prompt (Antigravity)

> Copy everything below the line into the Antigravity agent. Owner: Rangga. Reviewer afterwards: Claude (cross-QA, `AI_PERAN.md`).

---

You are the **Phase 1 backend writer** for NightVenue. The PM (GPT) approves Antigravity as the executor for this task; the model running inside Antigravity acts in the Backend Writer role of `AI_PERAN.md`. Claude reviews your output afterwards. You never review your own work.

## 0. Precondition gate (check BEFORE any file is created)

Stop and report `BLOCKED` (create nothing) unless ALL are true:

1. The canonical docs exist in `nightvenue-backend/docs/`: `PRD.md`, `DECISIONS.md`, `DATABASE.md`, `APPLICATION_ARCHITECTURE.md`, `UML.md`, `PHASES.md`, `AI_PERAN.md`, plus `nightvenue-backend/CONTEXT.md`.
2. They carry the **v2.1 delta patch** (check these markers, do not trust a filename):
   - `PRD.md` contains `CORE_PORTFOLIO_READY` and `BR-29`.
   - `DATABASE.md` contains `uq_vault_source_item`.
   - `DECISIONS.md` contains `Changelog v2.1`.
3. The Owner has stated in this chat that the PM gave `BACKEND_READY_FOR_PHASE_1 = YES`.

`DESIGN_SYSTEM.md` is a **frontend** gate. It is NOT required for this backend task. Do not block on it and do not create it.

If a canonical document conflicts with this prompt, **report the conflict, do not silently override either side**.

## 1. Step 1 — Audit the workspace first (no writes)

The workspace is a Windows folder `studikasus_hollywings` (PowerShell). Observed: the 8 canonical `.md` files at root, folders `nightvenue-backend/`, `nightvenue-frontend/`, and a tool cache `graphify-out/`.

Report, before changing anything:
- Contents of `nightvenue-backend/` and `nightvenue-frontend/` (two levels deep).
- Whether `nightvenue-backend/docs/` already contains the v2.1 docs (section 0). The old copies at the workspace root are superseded and must not be used.
- Whether the root, or either subfolder, is a git repository (`git rev-parse --show-toplevel` in each). There must end up being exactly **two** repos: `nightvenue-backend` and `nightvenue-frontend`. The root folder must **not** be a third repository. If it is, stop and ask.
- Whether Docker Desktop is running (`docker version`, `docker compose version`), JDK, Python, and Git are installed. Missing tools → items become `NOT VERIFIED`, never invented.
- Do not recreate or overwrite anything that already exists. Extend it, and list what you extended.
- Never touch `nightvenue-frontend/` and never commit `graphify-out/`.

## 2. Scope (infrastructure and skeleton only)

**In scope** (inside `nightvenue-backend/`): Spring Boot skeleton, FastAPI skeleton, Dockerfiles, `docker-compose.yml` (4 services), CI workflows, secret scanning, `.env.example`, `.gitignore`, `.gitattributes`, `.dockerignore`, `README.md`, `CONTEXT.md` version line, hosting spike evidence/checklist.

**Forbidden** (belongs to later phases): any JPA entity, Flyway dependency or migration (Phase 2), `BusinessDateService`, authentication/JWT/RBAC, order/payment/inventory/bottle/shift/chat/forecast code, ML classifier or forecast endpoints, Midtrans code, any frontend file. Do not add dependencies "for later".

## 3. Version selection (ADR-018): evidence required

Do not hardcode versions from memory or from old drafts (Java 21 / Spring Boot 3.3 / Python 3.11 / PostgreSQL 16 / Redis 7 are NOT defaults). Using official sources only (spring.io, python.org, postgresql.org, redis.io, endoflife.date, PyPI, Docker Hub), choose and pin:

- JDK (LTS) + Spring Boot (stable, supported, not RC/snapshot) + Maven wrapper.
- Python + FastAPI + uvicorn + pytest + Ruff.
- PostgreSQL major + Redis release (supported, official image tags).

Compatibility checks you must actually perform and document:
- Spring Boot ↔ JDK ↔ Maven plugins.
- **Ecosystem readiness for later phases**, checked now so Phase 2+ does not force a downgrade: confirm a Spring Boot–compatible release exists for springdoc-openapi, MapStruct, Flyway (Postgres module), Testcontainers.
- **Python wheels**: a brand-new Python release often has no wheels for scikit-learn/pandas yet. Choose the newest Python for which `fastapi`, `uvicorn`, `scikit-learn`, `pandas`, `joblib` all install from wheels on `linux/amd64` slim images (verify with `pip install --dry-run` or a real container build). Newest is not the criterion; working is.
- Pin base images by explicit tag (never `latest`).

Record the choices in the report with the source URL and date checked per version.

## 4. Target structure (inside `nightvenue-backend/`)

```text
api/
  pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/*, Dockerfile, .dockerignore
  src/main/java/com/nightvenue/api/NightVenueApiApplication.java
  src/main/resources/application.yml
  src/test/java/com/nightvenue/api/ (context + health test)
ml-service/
  requirements.txt            # at ml-service root, pinned
  Dockerfile, .dockerignore
  app/__init__.py, app/main.py
  tests/test_health.py
infra/                        # empty or hosting-spike notes only
.github/workflows/ci-api.yml, ci-ml.yml, docker.yml
docker-compose.yml, .env.example, .gitignore, .gitattributes, CONTEXT.md, README.md
docs/                         # canonical docs, already present, read-only for this task
```

The canonical docs already live in `docs/` (placed there by the Owner) and are the **only** source of truth. Do **not** copy, duplicate, or edit them. Never create a second copy elsewhere in the repo.

## 5. Spring Boot skeleton

- Dependencies: web, actuator, JDBC + PostgreSQL driver, Spring Data Redis, test starter, Testcontainers (PostgreSQL + Redis). **No JPA, no Flyway, no security.**
- `application.yml`: all environment-specific values from env vars with no secret defaults. Expose only `health` (and `info` if needed).
- Health: liveness probe independent of dependencies; readiness includes DB and Redis. The Docker healthcheck calls readiness.
- Tests: one context-load test and one health test using Testcontainers. If Docker is unavailable locally, mark the test run `NOT VERIFIED` (CI must still run it).
- Package root `com.nightvenue.api`. Create no business packages.

## 6. FastAPI skeleton

- Only `GET /health` → `{"status":"ok","service":"ml-service"}`. No `/ml/*` routes, no auth yet.
- Real pytest test for `/health`; Ruff config pinned; run as non-root in the image.

## 7. Docker and Compose

- Exactly four services: `api`, `ml-service`, `postgres`, `redis`.
- **Only `api` publishes a host port (8080).** `ml-service` must NOT be published to the host (`APPLICATION_ARCHITECTURE.md` section 6: internal Docker network only). Use `expose` and verify it with `docker compose exec`. `postgres` and `redis` are not published unless bound to `127.0.0.1` and documented as dev-only.
- Multi-stage Dockerfile for Java (build stage, minimal JRE runtime), non-root user, no secrets baked in.
- Healthchecks must use tools guaranteed to exist in that image. Do not assume `curl`, `wget`, or `bash`. For the Python image use a `python -c` HTTP probe; for the Java image either install the tool explicitly or use a mechanism that exists. Postgres: `pg_isready`. Redis: `redis-cli ping`.
- `depends_on` with `condition: service_healthy`.
- Required secrets in Compose use `${VAR:?message}` so an unset variable fails loudly instead of using a guessable default.

## 8. Environment variables

`.env.example` has safe non-secret examples and **empty** secret values:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/nightvenue
SPRING_DATASOURCE_USERNAME=nightvenue
SPRING_DATASOURCE_PASSWORD=
REDIS_URL=redis://redis:6379
ML_SERVICE_URL=http://ml-service:8000
ML_INTERNAL_TOKEN=
JWT_SECRET=
MIDTRANS_SERVER_KEY=
MIDTRANS_CLIENT_KEY=
```

README must explain how the developer creates an uncommitted `.env` with locally generated random values (give a PowerShell one-liner). `.env` is in `.gitignore`. No real or lookalike credential anywhere (source, Dockerfile, Compose, tests, README, workflows, logs).

## 9. Windows-specific traps (these cause rework, handle them now)

- `.gitattributes` forces LF for `mvnw`, `*.sh`, `Dockerfile*`, `*.yml`. A CRLF `mvnw` breaks inside Linux containers and CI.
- Ensure `mvnw` has the executable bit in git (`git update-index --chmod=+x api/mvnw`) and works via both `mvnw.cmd` (Windows) and `./mvnw` (CI/Linux).
- `.gitignore`: `.env`, `target/`, `__pycache__/`, `.venv/`, `.pytest_cache/`, `.ruff_cache/`, `graphify-out/`, IDE folders.

## 10. CI (GitHub Actions)

- Triggers: `pull_request` and `push` to `main`.
- `ci-api.yml`: compile, test (including Testcontainers tests), package, `docker build`.
- `ci-ml.yml`: install, Ruff, pytest, `docker build`.
- `docker.yml`: builds both images; pushes to GHCR **only on `main`**, with least-privilege `permissions` and `GITHUB_TOKEN`. No push on pull requests.
- Secret scanning (gitleaks or equivalent): verify the current usage and licensing requirements of the action you choose and pin a released version. Pin every third-party action to a version tag or SHA.
- Path filters are allowed only if they do not leave a required check permanently pending. Say how you handled that.
- No credentials in workflow files.

## 11. CONTEXT.md

Open the existing `nightvenue-backend/CONTEXT.md`. Change **only** the `Versi terpilih` line, and only with versions you actually verified. Do not alter any locked rule. `CONTEXT.md` must stay identical across both repos, so do **not** edit the frontend repo. Instead, output the exact line in your report so the frontend copy can be updated by its own task.

## 12. Hosting spike (ADR-019)

A Dockerfile is not a spike. Evaluate 1–2 realistic backend hosts. For each record: Spring Boot and FastAPI container support, managed PostgreSQL and Redis, RAM/CPU limits, cold start, public HTTPS, secret management, healthchecks, outbound access to Midtrans, expected cost, limitations. If you cannot actually deploy, ADR-019 stays **Proposed / NOT VERIFIED** and you output an exact manual checklist. Never mark it Accepted because the build succeeded. Do not edit `DECISIONS.md` yourself; propose the text in the report.

## 13. Validation (run for real; PowerShell is fine)

```text
docker compose config
docker compose build
docker compose up -d
docker compose ps          # postgres, redis, api, ml-service all healthy
```

Then: Maven test and package, `pytest`, `ruff check .`, `GET http://localhost:8080/actuator/health`, and the ml-service `/health` through `docker compose exec`. Also run the secret scan locally if the tool is available. Also verify that `ml-service` is **not** reachable from the host.

Rules: do not fabricate output. A check you could not run is `NOT VERIFIED` with the reason. `PASS` requires the command to have run successfully in this session, with the output quoted.

## 14. Git hygiene

Work on branch `chore/phase1-backend-foundation`. Do not commit, push, or force anything unless the Owner asks. Do not delete the Owner's files. Do not run destructive commands (`rm -rf`, `docker system prune`, `git reset --hard`).

## 15. Final report (exact format, no invented results)

```text
# NightVenue Phase 1 Backend Execution Report
## Preconditions (section 0): PASS / BLOCKED
## Workspace audit findings
## Files Created / Files Modified
## Versions Selected (JDK, Spring Boot, Maven, Python, FastAPI, PostgreSQL, Redis, base images) + source URL + date checked + reasoning
## Compatibility checks performed (incl. wheels, springdoc, MapStruct, Flyway, Testcontainers)
## Commands Actually Executed (verbatim) + output
## Docker Service Status
## Checklist
Backend skeleton / FastAPI skeleton / Docker build / compose up / Postgres health / Redis health /
Spring health / FastAPI health / ml-service not exposed to host / Java tests / Python tests / Lint /
Secret scan / CI config / Hosting spike  → each PASS | FAIL | NOT VERIFIED
ADR-019 resolved: YES | NO
## Exact CONTEXT.md "Versi terpilih" line for the frontend copy
## Remaining Issues
## Deviations from canonical documents: None | list
```

Do not claim "Phase 1 complete". This task covers the backend half only; the frontend skeleton is a separate task. Do not start Phase 2.
