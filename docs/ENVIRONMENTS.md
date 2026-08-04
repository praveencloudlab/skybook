# The Environment Ladder

```
LOCAL → DEV → SIT → TEST/QA → PERF → UAT → STAGING/PRE-PROD → PROD
                                                            ↘ DR (weekly drill)
```

Every environment in that line exists and runs. This document is the map: what
each rung is for, what gates it, where it physically lives, and — because this
platform ships on one free-tier VM and one laptop — exactly how a full ladder
maps onto that estate without pretending to be a datacentre.

## 1. The principle that carries everything

**Build once, promote many.** `ci.yml` builds every image once per commit,
scans it with Trivy, and pushes it to GHCR tagged with the commit SHA — as a
multi-architecture manifest, because the runners are amd64 and the production
VM is arm64. From that moment nothing is ever rebuilt: `promote.yml` walks the
*same digests* up the ladder, and `docker-compose.ladder.yml` is the overlay
that swaps `build:` for those images. When PROD runs, it runs the bytes QA
certified — "works on my machine" is structurally impossible to say, because
there is no per-environment machine to have built it on.

Configuration varies per environment the twelve-factor way: environment
variables, one file per rung under `deploy/environments/`, layered with
`--env-file`. Code never branches on the environment name.

## 2. The rungs

| Rung | Where it runs | Trigger | The question it answers | The gate |
|---|---|---|---|---|
| **LOCAL** | developer laptop | `docker compose up` | does my change work at all? | the developer |
| **DEV** | ephemeral, CI runner | every main commit (after CI) | does the promoted artifact boot and serve its front door? | health + smoke (`smoke.sh`) |
| **SIT** | ephemeral, CI runner | after DEV | do the services agree with *each other*? | `sit-probes.sh` — gateway routing, service-to-service credentials, an event across Kafka into the mail sink |
| **TEST/QA** | ephemeral, CI runner | after SIT | does the customer journey hold, end to end? | the full e2e certification suite, against the promoted images |
| **PERF** | ephemeral, CI runner | after QA | is it fast enough, still? | k6 thresholds in `perf/k6/journey.js` — a breach stops the promotion |
| **UAT** | evidence + human gate | after PERF | does the business accept it? | a person approves the `uat` environment against the recorded acceptance transcript |
| **STAGING** | the prod VM, transient project | after UAT approval | does it run on the real host, real architecture, real secrets? | `smoke.sh` on the VM, then teardown |
| **PROD** | the Oracle VM behind Caddy | after STAGING | — | health through the TLS front door, then a post-deploy backup |
| **DR** | weekly drill, CI runner | Monday 04:00 UTC + on demand | do the backups actually restore? | row-count verification against the backup manifest + a live smoke on the restored data |

Ephemeral rungs generate throwaway secrets per run (`ephemeral-env.sh`) — the
pattern the nightly certification established. Each rung seeds its own data
(`scripts/seed/seed.sh <container>`), so environments share nothing, not even
by accident.

## 3. What distinguishes the rungs beyond geography

- **DEV** is loud (`LOG_LEVEL=DEBUG`) and bootstraps a demo admin; its job is
  to make the first failure of a bad artifact cheap and legible.
- **SIT** exists because unit suites prove services alone and QA proves the
  journey; the space between — "booking-service can actually mint a service
  token and call flight-service", "an auth event becomes an email" — is where
  integration breaks hide. Its probes each cross at least one service boundary.
- **QA** runs the identical certification suite the nightly runs, with the same
  documented overrides (`docker-compose.e2e.yml`) — but against promoted
  images, which upgrades the claim from "the source passes" to "the artifact
  heading to production passes".
- **PERF** raises the gateway's per-source rate limit
  (`docker-compose.perf.yml`) because a load generator is one address
  pretending to be hundreds of users, and against the default cap k6 measures
  the limiter, not the platform (first observed: 91.5% of requests 429'd with
  the platform idle underneath). Consequence stated as ever: PERF does not
  certify the limiter; the gateway's own tests do.
- **UAT**'s evidence is a transcript of the real business journey — search
  anonymously, register, book, watch the payment row arrive off the bus,
  capture, reach CONFIRMED, price a cancellation — uploaded as an artifact.
  The approval recorded by GitHub on the `uat` environment *is* the sign-off.
- **STAGING** is transient by design: a 24 GB VM carrying seventeen containers
  cannot host a standing twin. The rehearsal stands up beside prod on shifted
  loopback ports, proves the artifact on the real host and architecture with
  the real secret set, and is torn down win or lose. It does not rehearse the
  TLS door — there is one 443 and it belongs to prod; prod's own verification
  covers it. Both limits are the honest cost of a one-VM estate.
- **PROD** deploys by pulling digests, never by building, and ends by taking a
  backup — so the newest restore point always brackets the newest release.
- **DR** is the rung most ladders only claim. Weekly, in public: seed a stack,
  write real data through the API, back it up, *destroy the database volume*,
  restore, verify every row count against the manifest, then prove the
  platform serves the restored data. `docs/DR_RUNBOOK.md` carries the RTO/RPO
  arithmetic and the human procedure for the real event.

## 4. Honesty section — what this ladder is and is not

- The ephemeral rungs are real environments by behaviour (isolated, gated,
  configured per rung) but they live for minutes on shared runners. On a
  funded estate the same files point at long-lived hosts; nothing in the
  design changes, only the substrate.
- SIT/QA/PERF run sequentially, not as standing parallel estates. The
  sequence *is* the ladder; the parallelism money buys is speed, not rigour.
- UAT is an approval-against-evidence gate, not a hosted environment a
  business user clicks around in. With one VM, hosted UAT would either evict
  staging or share prod; the evidence transcript was chosen as the least
  dishonest of the three.
- The frontend image promotes on its own tag (`FRONTEND_IMAGE_TAG`, default
  `latest`) because it ships from its own workflow; a backend promotion does
  not imply a frontend change.

## 5. Rollback

Two mechanisms, by failure mode:

- **Bad release, good data** — promote the previous SHA: run *Promote* with
  `image_tag` set to the last good commit. Same ladder, same gates, previous
  artifact.
- **Bad data** — `docs/DR_RUNBOOK.md`. Every prod deploy ends with a backup,
  so the restore point immediately before any release exists by construction.

## 6. One-time setup (the two things only a human can click)

1. **The UAT gate** — GitHub → Settings → Environments → `uat` → *Required
   reviewers* → add yourself. From then on every promotion pauses at UAT until
   the evidence is reviewed and approved. (The `staging` and `production`
   environments accept the same treatment if you want a second explicit gate.)
2. **VM deploys** — add repository secrets `VM_SSH_HOST`, `VM_SSH_USER`,
   `VM_SSH_KEY` (a dedicated deploy key for the VM), then set repository
   variable `DEPLOY_SSH_READY=true`. Until then the ladder runs and gates
   everything up to UAT, and the VM keeps its manual deploy path.
3. **(Once)** make the GHCR packages public — or leave them private; the
   pipeline authenticates with its own token either way, and the VM's docker
   can `docker login ghcr.io` with a read-only PAT if the packages stay
   private.
