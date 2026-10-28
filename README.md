# NightVenue Backend

Backend for **NightVenue**, a fictional nightlife venue operations platform (POS, inventory, payments, bottle keeping, cashier shifts, hybrid chat). Built as an engineering portfolio project. Not affiliated with any real brand.

## Status

| Area | State |
|---|---|
| Phase 1 foundation (skeleton, Docker Compose, CI) | Complete |
| Phase 2 database, business date, demo seed | Implemented |
| Business features (orders, payments, inventory, ...) | Not implemented yet |
| Performance and ML metrics | None published. Numbers will appear only after they are measured |
| Hosting | Not decided (ADR-019 is *Proposed*) |

## Stack

Spring Boot (Java), FastAPI (Python), PostgreSQL, Redis, Docker Compose, GitHub Actions. Exact pinned versions are in [`CONTEXT.md`](CONTEXT.md).

## Services

| Service | Role | Host access |
|---|---|---|
| `api` | Spring Boot. System of record and all business logic | `localhost:8080` |
| `ml-service` | FastAPI. Chat intent classifier and forecasting (later phases) | **Not published** (internal Docker network only) |
| `postgres` | PostgreSQL | `127.0.0.1:5433` (dev only) |
| `redis` | Cache, rate limit, refresh tokens | `127.0.0.1:6379` (dev only) |

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
- No business endpoints yet.
- Live deployment is unverified.

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
