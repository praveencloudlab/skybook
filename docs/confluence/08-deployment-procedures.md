# Deployment Procedures

This page is the operational reference for getting SkyBook code into an environment: the normal promote-to-production path, what the production VM deploy actually executes, the staging rehearsal semantics, rollback, local development deployment, and the (future-state) Kubernetes manifests. It is written for engineers releasing a change and for anyone on call who needs to know what a deploy did to the box. The pipeline design behind the ladder is covered in [04-cicd-release-process.md](04-cicd-release-process.md); this page focuses on the deploy mechanics themselves.

## 1. Normal release path

There is one way to production: push to `main` and let the ladder carry the commit.

1. **Push to `main`.** The CI workflow (`ci.yml`) builds every service image once, scans it, and pushes it to GHCR tagged with the commit SHA (`ghcr.io/praveencloudlab/skybook-<service>:<sha>`).
2. **Promote triggers automatically.** `.github/workflows/promote.yml` runs on `workflow_run` completion of CI on `main` (successful runs only). Nothing is ever rebuilt after this point — every rung runs the exact digests CI pushed (`docker-compose.ladder.yml` is the build-once-promote-many mechanism, keyed on `IMAGE_TAG`).
3. **The ladder runs:** DEV → SIT → TEST/QA → PERF → UAT → STAGING → PROD. DEV/SIT/QA/PERF are ephemeral compose stacks on the runner with per-run throwaway secrets (`.github/scripts/ephemeral-env.sh`); STAGING and PROD are the standing jobs against the Oracle Cloud VM.
4. **Human approval at UAT.** The `uat` GitHub environment carries a required-reviewers protection rule; the ladder pauses at "Waiting for review" until a person approves against the uploaded acceptance-evidence artifact. The same protection can optionally be applied to the `production` environment for a second explicit yes (docs/ENVIRONMENTS.md §6.1).
5. **STAGING then PROD deploy over SSH** — but only when the repository variable `DEPLOY_SSH_READY` is `true` and the SSH secrets exist. Until that switch is flipped, the ladder still runs and gates everything up to UAT.

The workflow can also be started manually (**Actions → Promote → Run workflow**) with an optional `image_tag` input naming the commit SHA to promote — this is also the rollback mechanism (section 4). The frontend image promotes on its own tag (`FRONTEND_IMAGE_TAG`, default `latest`) via its separate workflow; a backend promotion does not imply a frontend change.

## 2. What the VM deploy actually does

The PROD job SSHes into the VM (key/host/user from repository secrets) and pipes `.github/scripts/prod-on-vm.sh` to `bash -s <image-tag>`. On the VM the script:

| Step | Command / behaviour |
|---|---|
| 1. Sync the checkout | `cd ~/skybook`, `git fetch origin main`, `git checkout <tag>` — compose files and scripts on the VM match the promoted commit exactly |
| 2. Compose invocation | `docker compose --env-file .env --env-file deploy/environments/prod.env -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.prod.yml` |
| 3. Pull certified images | `export IMAGE_TAG=<tag>` then `pull -q` — the same digests QA certified |
| 4. Roll the stack | `up -d --no-build --remove-orphans` |
| 5. Health wait | `.github/scripts/wait-healthy.sh skybook 420` — blocks until no container is `starting` and none is `unhealthy`, or fails loudly with the `ps` table after 420 s |
| 6. Front-door probe | Reads `SKYBOOK_DOMAIN` from the VM's `.env` and curls `https://$SKYBOOK_DOMAIN/` (up to 12 attempts, 10 s apart) — one probe exercises the Caddy TLS certificate, the proxy, and the application together |
| 7. Post-deploy backup | `scripts/backup.sh ./backups skybook-postgres-1 14` — the newest restore point always brackets the newest release (docs/DR_RUNBOOK.md §4) |

Production identity comes from `deploy/environments/prod.env` (`COMPOSE_PROJECT_NAME=skybook`, `SKYBOOK_ENV=prod`, a `JAVA_TOOL_OPTIONS` heap ceiling of `-Xmx768m`); everything secret or installation-specific (`POSTGRES_PASSWORD`, JWT keys, `MAIL_*`, `SKYBOOK_DOMAIN`, service client secrets) lives only in the VM's own `~/skybook/.env`, which never leaves the box. Later `--env-file` flags win, so the committed identity file cannot be overridden by a stale VM value.

The prod overlay (`docker-compose.prod.yml`) enforces the one-door principle: Caddy owns 80/443 and terminates TLS; the frontend (127.0.0.1:3000), gateway (127.0.0.1:8080), Mailpit (8025), Prometheus (9090), Grafana (3001), Loki (3100) and Tempo (3200) are all rebound to loopback, reachable only from the VM or an SSH tunnel (docs/DEPLOY_ORACLE.md).

## 3. Staging rehearsal semantics

STAGING is a dress rehearsal **on the production VM itself** — same host, same architecture, same secret set (staging deliberately reuses prod's `.env`; a rehearsal with different keys would not be rehearsing the show). `.github/scripts/staging-on-vm.sh` runs over SSH exactly like the prod script, with these differences:

- **Second, transient compose project.** `COMPOSE_PROJECT_NAME=skybook-staging` stands up *beside* the live `skybook` project. A `trap ... EXIT` tears it down with `down -v --remove-orphans` **win or lose** — a one-VM estate cannot afford a standing twin, and a rehearsal left running would eat the memory prod needs.
- **Application chain only.** It starts `postgres kafka mailpit otel-agent`, the seven backend services, the gateway and the frontend. The observability containers (Grafana/Prometheus/Loki/Tempo/Promtail) are never started: their bind-mounted `./docker-data/*` directories belong to live prod (a rehearsal once died on prod's Prometheus TSDB lock).
- **Isolated state.** The staging overlay (`docker-compose.staging.yml`) replaces prod's bind mounts with project-scoped named volumes (`staging-pgdata`, `staging-kafkadata`) so the rehearsal can never open prod's data directory.
- **Shifted loopback ports.** Prod keeps 3000/8080; staging answers on frontend 3900 and gateway 8180 (`STAGING_FRONTEND_PORT` / `STAGING_GATEWAY_PORT` in `deploy/environments/staging.env`), with its Mailpit on 8125. The smoke test's OTP redemption is pointed at the rehearsal's Mailpit, not prod's.
- **No Caddy.** There is one public TLS door on the VM and it belongs to prod; the certificate path is exercised by prod's own health verification instead.

After health wait (420 s), reference-data seeding, and the same smoke script DEV ran (`.github/scripts/smoke.sh` against 8180/3900), the rehearsal is torn down and the PROD job proceeds.

## 4. Rollback

Rollback is a **re-promotion of a previous good artifact**, not a special path:

1. GitHub → **Actions → Promote → Run workflow**, set `image_tag` to the last good commit SHA.
2. The ladder re-runs every gate on the way back down to prod — the rolled-back artifact is re-certified, not trusted on memory.

This is Scenario A in docs/DR_RUNBOOK.md ("bad deploy, data intact"): do not touch backups. If data is also corrupted, the post-deploy backup taken at step 7 of every prod deploy is the restore point immediately preceding the bad release (Scenario B, `scripts/restore.sh`); the weekly DR drill in the same workflow proves those backups actually restore.

## 5. Local deployment for development

LOCAL is the one environment that deliberately does **not** use the ladder overlay: a developer's inner loop needs `--build`, and the base `docker-compose.yml` provides build contexts for every service.

```bash
# full stack from source (requires a local ./.env — POSTGRES_PASSWORD,
# JWT_PRIVATE_KEY and JWT_PUBLIC_KEY are fail-fast required)
docker compose up -d --build

# rebuild only the service you changed
docker compose up -d --build booking-service
```

Locally published ports (base compose file):

| Endpoint | Port |
|---|---|
| API gateway | 8080 |
| Frontend | 3000 |
| Mailpit UI/API | 8025 |
| Prometheus | 9090 |
| Grafana | 3001 |
| Loki | 3100 |
| Tempo | 3200 |

Postgres (5432) and Kafka (9092) are intentionally **not** host-published — they are internal-only on the compose network; re-publish via a gitignored `docker-compose.override.yml` for local debugging. Reference data is seeded with `scripts/seed/seed.sh <postgres-container>`, the same script every ladder rung runs.

## 6. Future state: Kubernetes

A complete 28-file Kubernetes manifest tree exists on the **unmerged** branch `feature/kubernetes` (present on both local and `origin`): a kustomize base under `k8s/base/` (namespace, Postgres, Kafka, all seven services, gateway, and the observability stack, applied with `kubectl apply -k k8s/base` after copying `secrets.env.example`), plus a pinned, unmodified copy of the ingress-nginx `controller-v1.12.1` manifest under `k8s/ingress-nginx/` so cluster setup never depends on a live URL fetch. The tree targets a local Docker Desktop cluster (its ingress LoadBalancer binds to localhost there) and is currently blocked on enabling Docker Desktop Kubernetes. Until it merges, docker compose on the VM remains the only production deployment mechanism; the `/livez` and `/readyz` probes already on `main` are its groundwork.

## 7. Prerequisites and secrets

| Item | Kind | Purpose |
|---|---|---|
| `VM_SSH_HOST` | GitHub Actions secret | The VM's public address |
| `VM_SSH_USER` | GitHub Actions secret | `ubuntu` |
| `VM_SSH_KEY` | GitHub Actions secret | Full private half of the dedicated `skybook-pipeline` ed25519 deploy key — never a personal key, revocable by deleting one line in the VM's `authorized_keys` |
| `DEPLOY_SSH_READY` | GitHub Actions **repository variable** | The switch: STAGING and PROD jobs stay skipped until it reads `true` |
| `~/skybook/.env` on the VM | VM-local file | All prod (and staging-rehearsal) secrets: `POSTGRES_PASSWORD`, JWT keys, `MAIL_*`, `SKYBOOK_DOMAIN`, service client secrets — never committed, never leaves the VM |
| `uat` environment protection | GitHub environment rule | Required reviewers — the human acceptance gate; optionally mirrored on `production` |

Setup steps for all of the above are recorded in docs/ENVIRONMENTS.md §6.
