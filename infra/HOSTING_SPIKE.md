# NightVenue — Hosting Spike Notes (ADR-019)
# Status: Proposed / NOT VERIFIED
# ADR-019 will move to Accepted only after a successful live deployment is validated.

## Candidates Evaluated

### Option A: Railway.app

| Criterion | Assessment |
|---|---|
| Spring Boot container support | Yes — arbitrary Docker containers on Starter/Pro plans |
| FastAPI container support | Yes |
| Managed PostgreSQL | Yes (Railway Postgres plugin, 1 GB free on Starter) |
| Managed Redis | Yes (Railway Redis plugin) |
| RAM/CPU limits | Starter: 512 MB RAM, shared CPU; Pro: 8 GB RAM, 8 vCPU max |
| Cold start | Containers idle after 30 min inactivity on Starter; Pro has always-on |
| Public HTTPS | Yes — auto-provisioned per service subdomain |
| Secret management | Railway environment variables UI; no external vault needed for MVP |
| Healthchecks | Via healthcheck path setting in Railway service config |
| Outbound Midtrans (api.midtrans.com) | Yes — no outbound IP restrictions |
| Cost estimate | Starter free tier (limited hours); Pro ~$20/month base |
| Limitations | Starter has ~500 hours/month execution limit; no persistent disk except volumes |

**Manual verification checklist (NOT VERIFIED — deploy required):**
- [ ] Deploy api image from GHCR to Railway service
- [ ] Set environment variables via Railway dashboard / CLI
- [ ] Verify healthcheck endpoint is reachable from Railway health monitor
- [ ] Verify ml-service is NOT accessible publicly (internal service only)
- [ ] Confirm outbound HTTPS to `api.midtrans.com` succeeds from api container
- [ ] Confirm PostgreSQL plugin connects via `SPRING_DATASOURCE_URL`
- [ ] Confirm Redis plugin connects via `REDIS_URL`

---

### Option B: Fly.io

| Criterion | Assessment |
|---|---|
| Spring Boot container support | Yes — Dockerfile-based deployment via flyctl |
| FastAPI container support | Yes |
| Managed PostgreSQL | Yes (Fly Postgres — managed by user, Fly-hosted) |
| Managed Redis | Yes (Upstash Redis add-on available) |
| RAM/CPU limits | Free: 256 MB RAM, 1 shared CPU; Paid: up to 32 GB |
| Cold start | Machines stop if idle; ~2–5s cold start typical |
| Public HTTPS | Yes — auto TLS via Let's Encrypt |
| Secret management | `fly secrets set` — stored encrypted, injected as env vars |
| Healthchecks | Configured in `fly.toml` → `[[services.http_checks]]` |
| Outbound Midtrans (api.midtrans.com) | Yes — full internet access |
| Cost estimate | Free tier covers 2 shared-cpu-1x VMs + 3 GB storage; beyond that ~$7+/month |
| Limitations | Free Postgres is user-managed (no automatic failover); Upstash Redis has 10k request/day free limit |

**Manual verification checklist (NOT VERIFIED — deploy required):**
- [ ] `flyctl launch` in api/ and configure fly.toml
- [ ] `flyctl launch` in ml-service/ with internal networking
- [ ] Set secrets: `flyctl secrets set SPRING_DATASOURCE_PASSWORD=... JWT_SECRET=...`
- [ ] Verify `/actuator/health` is accessible via public HTTPS URL
- [ ] Verify ml-service is accessible internally but NOT from public internet
- [ ] Confirm outbound HTTPS to `api.midtrans.com` succeeds
- [ ] Confirm PostgreSQL connection string resolves within Fly private network

---

## Proposed ADR-019 Update Text (do not apply to DECISIONS.md without PM review)

```
### ADR-019 — Hosting Platform (spike results)
Status: Proposed
Options: Railway.app (Option A), Fly.io (Option B)
Result: NOT VERIFIED — deployment not yet executed. Manual checklist in infra/HOSTING_SPIKE.md.
Decision: Pending Owner selection and successful live deployment validation.
Next step: Owner selects Option A or B; PM approves; execute deployment; mark Accepted.
```
