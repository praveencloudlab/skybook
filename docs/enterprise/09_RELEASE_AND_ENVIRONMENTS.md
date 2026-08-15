# SKB-DOC-09 — Release & Environments Policy

| | |
|---|---|
| **Document ID** | SKB-DOC-09 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Detail source** | `docs/ENVIRONMENTS.md` (ladder & Promote pipeline), `docs/DEPLOY_ORACLE.md` (production bootstrap) |

## 1. Environment ladder

| Environment | Purpose | Composition | Data |
|---|---|---|---|
| **Local** | Development | `docker compose up --build`, all ports on localhost, Mailpit for mail | Seeded schedule; developer test accounts |
| **CI** | Gatekeeping | Fresh containers per run (Testcontainers); clean state by construction | Ephemeral |
| **Certification** | Release proof | Clean full-stack deployment; E2E suite drives real journeys | Fresh seed each run |
| **Production** | Live | Same compose + `docker-compose.prod.yml` overlay (Caddy TLS single door, localhost-bound internals) on the production VM | Real; backed up per DR runbook |

One artefact set serves every rung: dev and prod differ by the overlay file
and `.env`, never by divergent images (NFR-08). The certified images are the
ones promoted — production does not rebuild from source except in the
documented bootstrap/fallback path.

## 2. Versioning & branches

`main` is always releasable. Features integrate by PR with the full gate
(SKB-DOC-08 §7). Images are tagged by commit SHA by the CI matrix; "the
release" is a SHA that passed certification. Rollback is redeploying the
previous certified SHA (state: see §4).

## 3. Release procedure

1. PR merged to `main` with all gates green.
2. Certification run on a clean stack; failures stop the release — no
   manual-fix-and-continue on the certification host.
3. Promote: the pipeline pulls the certified images to production and
   restarts services in dependency order (`ENVIRONMENTS.md` §Promote;
   one-time SSH setup in its §6).
4. Post-deploy verification: public URL loads over TLS, health groups green,
   one end-to-end smoke journey (search → book → cancel on a test account),
   observability dashboards receiving.
5. The release notes are the merged PR descriptions; anything
   operator-visible (new env var, new port, migration with a backfill) must
   be called out explicitly in the PR that introduces it.

## 4. Schema & data during releases

Flyway migrates forward automatically at service start. Because migrations
are one-release backward-compatible (SKB-DOC-05 §4.2), rolling back the
*code* one release never requires a schema rollback. Data-destructive
migrations are release-gated as described there. Backups precede every
production promote (DR runbook §pre-deploy).

## 5. Configuration & secrets per environment

All environment differences live in `.env` (validated fail-fast) plus the
overlay file. The production domain (`SKYBOOK_DOMAIN`) drives both TLS and
the public base URL used in outbound e-mail links — one variable, one
source of truth. Secrets never move down the ladder: production values
exist only on the production host.

## 6. Emergency changes

A hotfix follows the same path at higher speed — PR, gates, certification,
promote. The only sanctioned shortcut: certification may run the targeted
subset plus the smoke journey when the fix is S1 and the full run's delay
adds user harm; the full run then executes immediately after promote, and
its failure reopens the incident.
