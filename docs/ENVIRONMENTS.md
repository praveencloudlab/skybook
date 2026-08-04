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

Every rung that runs the ladder overlay also runs with a JVM ceiling
(`JAVA_TOOL_OPTIONS`, per env-file): without a cgroup limit each service
sizes its heap against the whole host, and eight of those on one 16 GB
runner starved the database out from under the very first DEV deploy. The
first thing the ladder ever caught was itself.

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

## 6. One-time setup — the exact steps

Performed once, on 5 August 2026, in about ten minutes. Recorded here in
full because "add the secrets" is the kind of instruction that assumes the
reader already knows everything the instruction exists to teach.

### 6.1 The UAT approval gate (browser only, ~2 minutes)

Makes every promotion pause after PERF and wait for a human.

1. Open `https://github.com/<owner>/skybook/settings/environments`
   (repo → **Settings** → **Environments** in the left sidebar).
2. The environments already exist — the ladder created them on its first
   run: `dev`, `sit`, `qa`, `perf`, `uat`, `staging`, `production`, `dr`.
   Click **`uat`**.
3. Under **Deployment protection rules**, tick **Required reviewers**.
4. Search for and add the approving account (up to six can be listed).
5. **Save protection rules.**

From the next run onward the ladder stops at UAT showing *"Waiting for
review"*; the reviewer opens the run, reads the acceptance evidence
artifact, and clicks **Review deployments → approve**. The same treatment
on `production` adds a second explicit yes before the VM deploy, if wanted.

### 6.2 The pipeline's SSH access to the VM (~8 minutes)

The pipeline gets a **dedicated deploy key** — never a personal one — so it
can be revoked on its own by deleting one line on the VM.

On the workstation (Git Bash):

```bash
# 1. generate the keypair; no passphrase, CI cannot type one
ssh-keygen -t ed25519 -f ~/.ssh/skybook-deploy -N "" -C "skybook-pipeline"

# 2. authorize the public half on the VM (uses your existing access once)
ssh ubuntu@<vm-address> "echo $(cat ~/.ssh/skybook-deploy.pub) >> ~/.ssh/authorized_keys"

# 3. prove the new key works before telling GitHub about it
ssh -i ~/.ssh/skybook-deploy ubuntu@<vm-address> "echo pipeline key OK"

# 4. print the private key for the secret paste (include BEGIN/END lines)
cat ~/.ssh/skybook-deploy
```

In the browser, `Settings → Secrets and variables → Actions`:

| Kind | Name | Value |
|---|---|---|
| Secret | `VM_SSH_HOST` | the VM's public address |
| Secret | `VM_SSH_USER` | `ubuntu` |
| Secret | `VM_SSH_KEY` | the full private key from step 4 |
| **Variable** (Variables tab) | `DEPLOY_SSH_READY` | `true` |

The variable is the switch: the STAGING and PROD jobs check it and stay
skipped until it reads `true`, which is why adding the secrets alone
changes nothing until the deliberate flip.

To revoke the pipeline's access later: delete the `skybook-pipeline` line
from `~/.ssh/authorized_keys` on the VM and set `DEPLOY_SSH_READY` to
anything else.

### 6.3 First full walk — what to expect

Trigger: **Actions → Promote → Run workflow** (leave `image_tag` empty to
promote the branch head), or just push to `main`.

- **~25 minutes**: DEV → SIT → QA → PERF, as on every run.
- **The pause**: *"Waiting for review"* at UAT. Approve when satisfied —
  it waits indefinitely and expires the run after 30 days.
- **STAGING, first time only**: the VM pulls the promoted arm64 images
  (~2 GB cold, cached afterwards) — allow extra minutes. The rehearsal
  stack stands beside live prod on shifted loopback ports, gets seeded and
  smoked, and is torn down win or lose.
- **PROD**: pulls the same digests into the live project, rolls the stack,
  verifies through the TLS front door, and ends with an automatic backup.

Sizing caveat, stated because it bit the design once already: the
transient-staging pattern was sized for a 24 GB host. On a 16 GB host two
full stacks fit only because of the per-environment JVM ceilings, and
tightly — if the rehearsal ever OOMs there, the accepted trim is starting
the staging project without its observability containers.

### 6.4 Registry visibility

GHCR packages under this repository are public, so neither the runners nor
the VM need registry credentials to pull. If they are ever made private:
the pipeline side keeps working (it logs in with its own job token), and
the VM needs a one-time `docker login ghcr.io` with a read-only PAT.

## 7. Operating the ladder day to day

The entire release process after setup:

1. **Push to `main`.** CI builds, scans and publishes the images; the
   ladder walks DEV → SIT → QA → PERF unattended.
2. **Approve at UAT** when the evidence artifact satisfies you.
3. There is no step three — STAGING rehearses, PROD deploys, the backup
   lands, and the run goes green.

Useful handles:

- **Promote a specific commit** (including going *backwards* for a
  rollback): Actions → Promote → Run workflow → set `image_tag` to the
  full SHA. Same gates, chosen artifact.
- **Every rung leaves evidence**: the UAT acceptance transcript and the k6
  summary are uploaded as artifacts on the run; QA uploads its failsafe
  reports — a failing rung names its failing tests without anyone
  spelunking logs.
- **The weekly DR drill** runs Monday 04:00 UTC on its own; a failed drill
  fails the workflow loudly. It can be run on demand from the same
  workflow dispatch.
- **The nightly certification** continues independently of the ladder, on
  the default compose project, as it always has.
