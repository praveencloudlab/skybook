# Environment Guide: LOCAL / DEV / SIT / QA / PERF / UAT / STAGING / PROD

This page is the reference map of every SkyBook environment: where each one runs, which compose project and ports it uses, what question it answers, and what happens to its data. It is written for developers debugging a rung of the promotion ladder, for reviewers approving the UAT gate, and for anyone who needs to know which URL (or which artifact) to look at for a given environment. The pipeline that drives these environments is `.github/workflows/promote.yml`; the per-environment configuration lives in `deploy/environments/*.env`.

## Environment matrix

| Environment | Runs on | Compose project | Purpose | Gateway | Frontend | Mailpit UI | Root log level | Data lifecycle |
|---|---|---|---|---|---|---|---|---|
| LOCAL | Developer machine | default (directory name) | Inner-loop development with `--build` | `localhost:8080` | `localhost:3000` | `localhost:8025` | INFO (base default) | Persistent — bind mounts under `./docker-data/` |
| DEV | GitHub Actions runner | `skybook-dev` | First landing of every `main` commit: does the promoted artifact boot and serve its front door? | `localhost:8080` (runner) | `localhost:3000` (runner) | `localhost:8025` (runner) | DEBUG | Transient — discarded with the runner |
| SIT | GitHub Actions runner | `skybook-sit` | System integration: do the services agree with each other through the gateway, the bus, and the mail sink? | `localhost:8080` (runner) | `localhost:3000` (runner) | `localhost:8025` (runner) | INFO | Transient — discarded with the runner |
| TEST/QA | GitHub Actions runner | `skybook-qa` | E2E certification: the nightly customer-journey suite, run against the promoted images | `localhost:8080` (runner) | `localhost:3000` (runner) | `localhost:8025` (runner) | INFO | Transient — discarded with the runner |
| PERF | GitHub Actions runner | `skybook-perf` | k6 load with thresholds as the gate | `localhost:8080` (runner; k6 hits `api-gateway:8080` on the compose network) | `localhost:3000` (runner) | `localhost:8025` (runner) | WARN | Transient — discarded with the runner |
| UAT | GitHub Actions runner | `skybook-uat` | Acceptance journey + human sign-off (required-reviewer rule on the `uat` GitHub environment) | `localhost:8080` (runner) | `localhost:3000` (runner) | `localhost:8025` (runner) | INFO | Transient — discarded with the runner |
| STAGING | Production VM (Oracle Cloud) | `skybook-staging` | Dress rehearsal beside live prod: same host, same architecture, same secrets, promoted images | `127.0.0.1:8180` | `127.0.0.1:3900` | `127.0.0.1:8125` | INFO | Transient — `down -v` after the smoke, win or lose; state in project-scoped named volumes (`staging-pgdata`, `staging-kafkadata`) |
| PROD | Production VM (Oracle Cloud), behind Caddy | `skybook` | The live platform | `127.0.0.1:8080` (VM-only; public traffic enters via Caddy on 80/443) | `127.0.0.1:3000` (VM-only) | `127.0.0.1:8025` (VM-only) | INFO | Persistent — bind mounts under `./docker-data/`, post-deploy backups |

Promotion order (the `needs` chain in `promote.yml`): **DEV → SIT → TEST/QA → PERF → UAT → STAGING → PROD**, plus a weekly **DR drill** (Mondays 04:00 UTC) that stands up a `skybook-dev` stack from `:latest`, backs it up, destroys the database, restores, and re-verifies through the API.

## How an environment is assembled

Every environment above LOCAL runs the **same images**, built once per commit by CI, scanned, and pushed to GHCR tagged with the commit SHA (`ghcr.io/praveencloudlab/skybook-*:<sha>`). The ladder overlay `docker-compose.ladder.yml` swaps the base file's local builds for those promoted images via `IMAGE_TAG`, and injects exactly three per-environment knobs: `SPRING_PROFILES_ACTIVE` (the environment name, visible in logs and actuator), `LOGGING_LEVEL_ROOT`, and a `JAVA_TOOL_OPTIONS` heap ceiling (default `-Xmx640m -XX:MaxMetaspaceSize=256m` for the 16 GB runners; `prod.env` raises it to `-Xmx768m` for the 24 GB VM). The frontend image is tagged independently (`FRONTEND_IMAGE_TAG`, default `latest`) because it ships from its own workflow.

Each rung is composed as:

| Environment | Env file | Compose files |
|---|---|---|
| LOCAL | `./.env` only (no ladder file, on purpose) | `docker-compose.yml` (with `--build`) |
| DEV / SIT / UAT | `deploy/environments/{dev,sit,uat}.env` | `docker-compose.yml` + `docker-compose.ladder.yml` |
| TEST/QA | `deploy/environments/qa.env` | base + ladder + `docker-compose.e2e.yml` (widened check-in/boarding windows, 1-minute hold/draft TTLs, raised rate limit, guest cookie `Secure` off — each a stated certification trade-off) |
| PERF | `deploy/environments/perf.env` | base + ladder + `docker-compose.perf.yml` (rate limit raised to 10000 req/min so k6 measures the platform, not the limiter) |
| STAGING | `deploy/environments/staging.env` | base + ladder + `docker-compose.staging.yml` |
| PROD | `deploy/environments/prod.env` | base + ladder + `docker-compose.prod.yml` |

Note that `--env-file` **replaces** the default `./.env`, so every variable the compose files need must come from the named file or the process environment; later `--env-file` flags win, which is how `prod.env`'s identity values override any stale value in the VM's own `.env` while the secrets still come only from the VM.

## Secrets

Nothing secret is committed. The ephemeral rungs (DEV, SIT, QA, PERF, UAT, and the DR drill) generate throwaway secrets per run via `.github/scripts/ephemeral-env.sh` — a fresh RSA-2048 JWT keypair, random Postgres/Grafana/service-client passwords — written to a runner-local `.env` and destroyed with the runner. The standing environments (STAGING, PROD) read their secrets from the VM's own `~/skybook/.env`, which never leaves the machine. STAGING deliberately reuses prod's secret set: a dress rehearsal with different keys would not be rehearsing the show.

## Mail: every environment delivers to the sink

Every `deploy/environments/*.env` file sets `SPRING_MAIL_HOST=mailpit` and `SPRING_MAIL_PORT=1025`, and the base compose file defaults notification-service to the same. No environment on this ladder sends real email — booking confirmations, tickets, OTPs, and password resets all land in that environment's own Mailpit instance (UI on 8025; **8125** for staging, because prod's Mailpit owns 8025 on the shared VM — the staging smoke test reads its OTPs from the rehearsal's inbox, not prod's). UAT mirrors prod settings *except* mail, so acceptance runs never email a real inbox.

## STAGING: the shifted-port rehearsal

STAGING is transient and lives on the production VM (`.github/scripts/staging-on-vm.sh`). Because a one-VM estate cannot carry two standing stacks, the pipeline stands `skybook-staging` up beside live prod, seeds it, smokes it through the same front doors DEV used, and tears it down again with `down -v` whether it passed or failed. Three isolation measures let the two projects coexist for those minutes:

- **Shifted host ports** — gateway `127.0.0.1:8180`, frontend `127.0.0.1:3900`, Mailpit `127.0.0.1:8125` — so prod keeps 8080/3000/8025. (3900 rather than the obvious 31xx/32xx because prod's Loki sits on 3100 and Tempo on 3200.)
- **Named volumes** for Postgres and Kafka state instead of the base file's `./docker-data/*` bind mounts, which live prod owns.
- **No observability containers and no Caddy** — the rehearsal runs the application chain only; 80/443 belong to prod's single TLS door.

## PROD: one door

PROD runs on the Oracle Cloud VM with `docker-compose.prod.yml`: Caddy owns 80/443, terminates TLS for `SKYBOOK_DOMAIN` (Let's Encrypt), and fronts the frontend container, which proxies `/api` to the gateway. Everything the base file publishes for local development — gateway 8080, frontend 3000, Mailpit 8025, Prometheus 9090, Loki 3100, Tempo 3200, Grafana 3001 — is rebound to `127.0.0.1` on the VM, reachable only from the VM itself or through an SSH tunnel (see `docs/DEPLOY_ORACLE.md`). Postgres and Kafka are never host-published in any environment; they are compose-network-internal (`postgres:5432`, `kafka:9092`).

## LOCAL: the developer environment

LOCAL is deliberately the only environment without a `deploy/environments/` file: it is `docker-compose.yml` plus your own `./.env`, run with `--build` for the inner loop. Everything is on localhost:

| Component | URL / port |
|---|---|
| Frontend (SPA) | http://localhost:3000 |
| API gateway | http://localhost:8080 |
| Mailpit (captured email) | http://localhost:8025 |
| Grafana | http://localhost:3001 (admin / `GRAFANA_ADMIN_PASSWORD`; 3001 because 3000 is reserved for the frontend) |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |
| Tempo | http://localhost:3200 |
| Postgres / Kafka | not host-published — internal-only; re-publish via a gitignored `docker-compose.override.yml` for local debugging |

Data is persistent across restarts in `./docker-data/` bind mounts (Postgres, Kafka, Prometheus, Loki, Tempo, Grafana).

## How to access each environment

- **DEV / SIT / QA / PERF / UAT** exist only for the minutes their pipeline job runs, on the runner's own localhost. Their evidence outlives them as workflow artifacts: `qa-certification-reports-<sha>` (30 days), `perf-k6-<sha>` (30 days), and `uat-evidence-<sha>` (90 days), plus tail-of-log dumps on failure.
- **UAT sign-off**: the `uat` GitHub environment carries a required-reviewer rule, so the ladder pauses there until a person approves against the recorded evidence.
- **STAGING** is reachable only from the VM (loopback bindings) during its few-minute lifetime.
- **PROD** is public at `https://$SKYBOOK_DOMAIN` through Caddy; operational endpoints (Grafana, Prometheus, Mailpit, raw gateway) need SSH access to the VM.
- **STAGING and PROD jobs** activate only when the repository variable `DEPLOY_SSH_READY` is `true` and the `VM_SSH_KEY`/`VM_SSH_HOST`/`VM_SSH_USER` secrets exist; until then the ladder still runs and gates everything up to UAT.

Deeper background: `docs/ENVIRONMENTS.md` (the full ladder rationale), `docs/CI_CD_MODULE.md` (the build-once pipeline), `docs/DEPLOY_ORACLE.md` (the VM), `docs/DR_RUNBOOK.md` (the weekly drill this ladder automates), and `docs/E2E_CERTIFICATION_MODULE.md` (the QA suite and its overrides).
