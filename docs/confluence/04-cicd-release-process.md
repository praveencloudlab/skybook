# CI/CD Release Process

This page describes how a SkyBook commit travels from `git push` to the production VM: which GitHub Actions workflows fire, what each one proves, and where the ladder pauses for a human. It is the reference for engineers landing changes, reviewers approving the UAT/staging/production gates, and anyone diagnosing a stuck or failed promotion. All facts are grounded in `.github/workflows/*.yml` and `.github/scripts/*.sh`; the design rationale lives in-repo in `docs/CI_CD_MODULE.md` and `docs/ENVIRONMENTS.md`.

## Guiding principle: build once, promote many

CI builds, tests, scans and pushes every image exactly once, tagged with the commit SHA. Every environment after that — DEV through PROD — pulls those exact multi-arch manifests and runs them with `--no-build`. Nothing is rebuilt on the way to production, so the artifact QA certified is bit-for-bit the artifact PROD runs.

## Workflows and triggers

| Workflow | File | Triggers | Scope |
|---|---|---|---|
| CI | `.github/workflows/ci.yml` | Push to `main` / `feature/**`, PRs to `main` (ignores `frontend/**` and `*.md`), manual dispatch | Backend build, tests, Sonar, dependency scan, 8-service Docker matrix |
| Frontend | `.github/workflows/frontend.yml` | Push/PR touching `frontend/**` only (mirror-image path filter of CI) | Lint, typecheck, test, build, frontend image build/scan/push |
| E2E Certification | `.github/workflows/e2e.yml` | Nightly cron `0 2 * * *` (02:00 UTC), manual dispatch — deliberately **not** per-PR | Full-fleet customer-journey suite against a fresh source build |
| Promote | `.github/workflows/promote.yml` | `workflow_run` after **CI** completes successfully on `main`; manual dispatch (optional `image_tag`); cron `0 4 * * 1` (Mondays 04:00 UTC, DR drill only) | The environment ladder: DEV → SIT → TEST/QA → PERF → UAT → STAGING → PROD, plus the weekly DR drill |

The trigger chain for a normal release: push to `main` → CI builds/scans/pushes images tagged with the head SHA → CI success fires Promote via `workflow_run`, which carries that exact SHA (`github.event.workflow_run.head_sha`) as `IMAGE_TAG` up the ladder.

## What CI contains

The `build-and-verify` job (JDK 21 Temurin, `backend/` Maven reactor):

1. **Compile** — `mvn -B compile`.
2. **Unit + integration tests** — `mvn -B clean verify`; Surefire/Failsafe reports and JaCoCo coverage are uploaded as artifacts (14-day retention).
3. **SonarCloud scan** — `mvn -B sonar:sonar`, skipped gracefully until the `SONAR_TOKEN` secret is configured.
4. **Trivy dependency scan** — `scan-type: fs` over `backend/` so the *built* fat jars (transitive, actually-packaged dependencies) are scanned, not just the POM. Fails the build on fixable HIGH/CRITICAL findings (`ignore-unfixed: true`, `exit-code: 1`); accepted findings live in `.trivyignore`. SARIF is uploaded to GitHub code scanning (`category: trivy-fs`) even on failure.

Then the Docker pipeline, per service across the 8-image matrix (`api-gateway`, `auth-service`, `booking-service`, `checkin-service`, `flight-service`, `inventory-service`, `notification-service`, `payment-service`):

- **Build → scan → only then push.** The image is built into the local daemon (`load: true, push: false`), Trivy image-scanned with the same HIGH/CRITICAL fixable gate, and pushed only if the scan passed. An image is never published unscanned. Per-image SARIF uploads under `trivy-image-<service>`.
- **Multi-arch by native runners.** amd64 is pushed as `:<sha>-amd64` from `ubuntu-latest`; a parallel job builds `:<sha>-arm64` natively on `ubuntu-24.04-arm` (the production VM is an Oracle Ampere A1, arm64). The Trivy gate is not repeated on arm64: both architectures come from the identical Dockerfile and dependency tree in the same run, and the CVE verdict does not vary by instruction set.
- **Manifest stitch.** `docker buildx imagetools create` publishes `:<sha>` and `:latest` manifests referencing both arch images, so the same tag resolves to amd64 on the ladder's runners and arm64 on the VM.

Pushes happen only on push to `main`; PR runs build and scan but never push. `frontend.yml` applies the same scan-before-push and multi-arch discipline to the `skybook-frontend` image (Node 22, `npm ci` → lint → typecheck → test → build first).

## Image tagging and promotion model

| Tag | Produced by | Meaning |
|---|---|---|
| `ghcr.io/<owner>/skybook-<service>:<sha>-amd64` | CI amd64 job | Scanned arch-specific image |
| `ghcr.io/<owner>/skybook-<service>:<sha>-arm64` | CI arm64 job | Native arm64 sibling for the VM |
| `ghcr.io/<owner>/skybook-<service>:<sha>` | Manifest job | The promoted artifact — the multi-arch manifest every ladder rung pulls |
| `ghcr.io/<owner>/skybook-<service>:latest` | Manifest job | Convenience alias; used only by the DR drill, which proves backup/restore rather than a specific release |

Promotion means each rung (and finally the VM) pulls the digests behind the `:<sha>` manifest. The VM never builds from source.

## The Promote ladder, rung by rung

Every ephemeral rung (DEV/SIT/QA/PERF/UAT and the DR drill) follows the same skeleton: generate throwaway per-run secrets (`ephemeral-env.sh` — fresh RSA JWT keypair, random passwords, never real credentials), pre-create writable `docker-data/` bind mounts (`observability-dirs.sh`), compose the stack from the promoted images (`docker-compose.yml` + `docker-compose.ladder.yml` + a per-environment env file from `deploy/environments/`), block on container health (`wait-healthy.sh`), seed reference data, then run the rung's own proof. STAGING and PROD instead run over SSH on the production VM.

| Rung | Question it answers | What runs | Pass signal |
|---|---|---|---|
| **DEV** | Does the promoted artifact boot and serve its front door at all? | `smoke.sh`: SPA serves; anonymous LHR→DXB search returns real itineraries (not just a 200 — an empty result after seeding fails the rung); register → OTP redeemed from Mailpit → login → `/api/auth/me` | Smoke script exits 0 |
| **SIT** | Do the promoted services agree with *each other* as a set? | `sit-probes.sh`: gateway→flight-service tokenless read; booking-service→flight-service quote over the service-to-service credential path; auth→Kafka→notification→Mailpit (a password-reset email crosses the bus into the sink) | All three cross-boundary probes pass |
| **TEST/QA** | Has the artifact passed the full customer-journey certification? | The nightly e2e suite (`mvn -pl e2e-tests -Pe2e verify`) against the *promoted images* plus `docker-compose.e2e.yml` overrides; a CI admin is registered and promoted via `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` with a forced auth-service recreate | Certification suite green; Failsafe reports uploaded (30-day retention) |
| **PERF** | Does the artifact hold its latency and error budgets under load? | `grafana/k6` running `perf/k6/journey.js`: 15-VU browse ramp + 3-VU account stream; thresholds `http_req_failed rate<0.01`, p95 search <1500 ms, calendar <1200 ms, quote <1200 ms, auth <2500 ms | k6 thresholds not breached; summary uploaded as evidence |
| **UAT** | Does a human accept the business journey, against recorded evidence? | `uat-journey.sh`: search → register (OTP off Mailpit) → book → payment row appears off the bus → authorize → capture → booking reaches CONFIRMED → cancellation preview; transcript uploaded as `uat-evidence-<sha>` (90-day retention) | Journey passes **and** a required reviewer approves the `uat` environment — the ladder pauses here |
| **STAGING** | Does the artifact run on the *production host* — same architecture, same secrets? | `staging-on-vm.sh` over SSH: transient `skybook-staging` compose project beside live prod (application chain only, no observability), the DEV smoke re-run through shifted ports, teardown win or lose | Smoke passes on the VM through gateway :8180 / frontend :3900 |
| **PROD** | Promote the certified artifact. | `prod-on-vm.sh` over SSH: pull the certified digests, `up -d --no-build --remove-orphans`, health-wait, probe `https://$SKYBOOK_DOMAIN/` through Caddy (certificate + proxy + app in one probe), then a post-deploy backup (14 kept) so the restore point brackets the release | Front door answers over TLS; backup taken |
| **DR** (weekly, not per-release) | Do the backups actually *restore*? | Mondays 04:00 UTC on `:latest`: stand up a stack, write real data, back it up, destroy the Postgres volume entirely, restore from the archive, verify row counts against the manifest, restart services and re-run the smoke against restored data | Restore verifies and the platform reads the restored data |

## Gates and required setup

| Gate | Mechanism | Effect |
|---|---|---|
| CI success | `workflow_run` trigger, `conclusion == 'success'` check on the DEV job | A commit only enters the ladder after build + verify + scan + push succeeded for it |
| Trivy scans | `exit-code: 1` on fixable HIGH/CRITICAL | Vulnerable dependencies fail the build; vulnerable images are never pushed |
| k6 thresholds | Defined in `perf/k6/journey.js` | A budget breach stops promotion before a human ever looks at UAT |
| UAT sign-off | GitHub environment `uat` with a required-reviewer rule (`docs/ENVIRONMENTS.md` §6) | The ladder pauses until a person approves against the uploaded evidence transcript |
| `DEPLOY_SSH_READY` | Repository variable checked by the STAGING and PROD jobs (`if: vars.DEPLOY_SSH_READY == 'true'`) | Until set `true` (and the `VM_SSH_KEY` / `VM_SSH_HOST` / `VM_SSH_USER` secrets exist), the ladder still runs and gates everything up to UAT, but never touches the VM |
| `staging` / `production` environments | Each VM-touching job is bound to its GitHub environment | Environment protection rules (approvals) apply before any SSH deploy runs |

Every rung is a GitHub environment (`dev`, `sit`, `qa`, `perf`, `uat`, `staging`, `production`, `dr`), so protection rules can be tightened per rung without workflow changes.

## Operational nuances worth knowing

- **OTP redemption is part of the pipeline.** Registration gates login behind an emailed one-time code, so the DEV smoke, the STAGING rehearsal and the UAT journey all redeem a real 6-digit OTP the way a customer would — polling the Mailpit sink's search API (`/api/v1/search?query=to:<addr>`). This means the smoke proves the whole auth→Kafka→notification→mail chain, not just an HTTP endpoint.
- **Staging's Mailpit lives on port 8125.** On the VM, prod's Mailpit owns 8025, so `docker-compose.staging.yml` remaps the rehearsal's sink to 8125 and `staging-on-vm.sh` points the smoke's `MAILPIT` variable there. Polling prod's inbox finds nothing — the first staging walk after the OTP feature failed exactly there.
- **Staging is transient by design.** A one-VM estate cannot afford a standing twin; the rehearsal is stood up beside prod on shifted ports (gateway 8180, frontend 3900), runs the application chain only (no observability containers — they would collide with prod's `docker-data` bind mounts), and is torn down by an EXIT trap whether it passes or fails.
- **Failing rungs leave evidence.** QA uploads Failsafe reports, PERF uploads the k6 summary, UAT uploads the journey transcript, and every ephemeral rung dumps container logs on failure — the fleet is gone once the runner is reclaimed, so the artifact is the only diagnosable record.
- **The DR drill is a rung most ladders skip.** It runs weekly in public and turns "we have backups" into "our backups restore, verified against a manifest and a live API read."
