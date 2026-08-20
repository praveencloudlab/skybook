# Incident & Postmortem Log

This page is the running record of production, staging, and pipeline incidents on SkyBook — what broke, how it was found, why it happened, and what rule or test now prevents the class from recurring. It is written for engineers joining the project and for anyone debugging a symptom that looks familiar; every entry is grounded in a fix commit or a code comment in the repo. Severities use the scale from the Engineering Handbook (`docs/enterprise/07_ENGINEERING_HANDBOOK.md` §7): S1 money/data corruption, S2 user-blocking, S3 degraded, S4 cosmetic. Pipeline context (the DEV→SIT→QA→PERF→UAT→STAGING→PROD promote ladder) is described in [04-cicd-release-process.md](04-cicd-release-process.md); environment topology in [05-environment-guide.md](05-environment-guide.md); deploy mechanics in [08-deployment-procedures.md](08-deployment-procedures.md).

## Index

| # | Date | Title | Severity | Fix |
|---|------|-------|----------|-----|
| 1 | 2026-08-19 | STAGING smoke polled prod's Mailpit, not the rehearsal's | S3 (release-blocking) | `f5816ba` |
| 2 | 2026-08-20 | Flaky payment-reference collision test sank an untouched build | S3 (release-blocking) | `84b1e57` |
| 3 | 2026-08-19 | OTP attempt counter silently rolled back by `@Transactional` | S2 (security) | `6f9583e` |
| 4 | 2026-08-19 | Stale `/admin` returnTo steered a fresh registration into 403 | S2 | `7faf31c` |
| 5 | recurring (2026) | Stale locally-built Docker images made shipped fixes look absent | S3 (process) | clean-state rule |
| 6 | 2026-07-25 | All mail silently dropped: default SMTP was Gmail + placeholder creds | S2 | `6af506d` |
| 7 | 2026-08-05 | `ssh bash -s` stdin drain truncated the staging deploy script | S2 (gate integrity) | `90a8163` |
| 8 | 2026-08-04 | Arrival times authored on the origin's clock; stale `skybook-common` in images hid the fix | S2 | `1299999` |

---

## 1. STAGING rung failed: smoke read prod's Mailpit instead of the rehearsal's

| Field | Detail |
|---|---|
| Date | 2026-08-19 (first STAGING walk after the OTP feature; fix committed 2026-08-20) |
| Severity | S3 — release-blocking, no user impact |
| Impact | The STAGING rung failed with "verification email never arrived" while every runner-local rung (DEV, SIT, QA, PERF, UAT) passed; the promote ladder stalled short of PROD. |
| Detection | GitHub Actions: STAGING smoke failure on the OTP-redemption step immediately after the OTP feature (`6f9583e`) entered the ladder. |
| Root cause | The transient staging rehearsal runs beside prod on the same Oracle Cloud VM with its Mailpit remapped to `127.0.0.1:8125` (`docker-compose.staging.yml`), because prod's Mailpit owns `8025` on that host. `smoke.sh`'s new OTP redemption polled its default `http://localhost:8025` — prod's sink — so the rehearsal's verification email was never found. |
| Fix | `f5816ba` — `staging-on-vm.sh` exports `MAILPIT="http://127.0.0.1:8125"` for the staging smoke only, pointing OTP redemption at the rehearsal's own inbox. |
| Lessons | A rehearsal sharing a host with prod must have every side channel remapped **and every probe told about the remap** — mail sinks included. Five green runner-local rungs prove nothing about the one rung with a shared-host topology. |

## 2. Flaky `PaymentReferenceGeneratorTest` failed an untouched CI run

| Field | Detail |
|---|---|
| Date | 2026-08-20 |
| Severity | S3 — release-blocking, no user impact |
| Impact | A CI run that touched no payment code went red on `collisionsAreRareAcrossManyGenerations`, blocking an unrelated change. |
| Detection | Red build in GitHub Actions on a payment-service unit test in a build that had not modified payment-service. |
| Root cause | The test demanded **zero** collisions among 10,000 draws from a 32^6 (~1.07 billion) reference space. The birthday bound puts a collision at ~4.6% per run (~0.047 expected collisions), so the test failed a perfectly healthy generator about one run in 22. |
| Fix | `84b1e57` — tolerate up to 3 collisions (`seen.size() >= 10_000 - 3`). P[more than 3] ≈ 4e-7, so the pass is deterministic in practice, while genuine entropy loss still fails: a space 100× smaller expects ~5 collisions per 10k draws and trips the bound almost every run. The reasoning is preserved as a comment in `backend/payment-service/src/test/java/com/skybook/praveen/paymentservice/domain/PaymentReferenceGeneratorTest.java`. |
| Lessons | Tolerances on statistical properties must come from the math, not from optimism. A zero-tolerance assertion on a probabilistic outcome is a flake generator; the right bound still fails loudly on real degradation. |

## 3. OTP attempt counter silently rolled back by `@Transactional`

| Field | Detail |
|---|---|
| Date | 2026-08-19 (caught during live verification of the OTP feature, before merge) |
| Severity | S2 — security: brute-force cap ineffective |
| Impact | Wrong-code exceptions rolled back the transaction that had just incremented the `attempts` counter, so the increment was silently discarded — the 5-guess cap never engaged and a 6-digit code stayed guessable indefinitely. Caught live before reaching users. |
| Detection | Live verification of `verifyEmail` against the running stack ("caught live, not in the mocks" — javadoc in `backend/auth-service/.../service/AuthService.java`). |
| Root cause | `InvalidVerificationCodeException` and `TooManyVerificationAttemptsException` are the method's *normal answers*, but under plain `@Transactional` any thrown runtime exception rolls the transaction back — including the attempt-counter update written moments earlier. |
| Fix | Shipped inside `6f9583e`: `@Transactional(noRollbackFor = {InvalidVerificationCodeException.class, TooManyVerificationAttemptsException.class})` on `AuthService.verifyEmail`, keeping the counter real. Verified in the 20/20 live API checks (403 gate, cap, cooldown, no-enumeration). |
| Lessons | When an exception is a method's expected answer rather than a failure, it must be excluded from rollback or its side effects (counters, audit rows) vanish. Security counters in particular must be verified against the live system — mocks don't run the transaction manager. |

## 4. Stale `/admin` returnTo sent a fresh registration into "403 No access"

| Field | Detail |
|---|---|
| Date | 2026-08-19 |
| Severity | S2 — user-blocking on first sign-in |
| Impact | An expired admin-console session (or an anonymous visit to `/admin`) left `returnTo=/admin` behind in the browser; the next sign-in on that browser could be a passenger. A fresh registration was steered straight into the admin console's 403 page after redeeming its OTP. |
| Detection | Seen live during post-OTP verification of the registration funnel. |
| Root cause | The remembered `returnTo` destination was honoured unconditionally, without checking whether the newly signed-in principal could actually use it. |
| Fix | `7faf31c` — `session.landingFor(returnTo, isAdmin)` is now the single rule: a remembered destination is honoured only when the signed-in principal can use it; a stale `/admin` falls back to home for a passenger, and the roleless fallback stays admins → `/admin`, passengers → `/`. Applied at all three consumption sites (SignInPage, RegisterPage, Google sign-in button). Live-verified: anonymous `/admin` → register → OTP → lands home with no 403; the admin's expired-session resume still works. |
| Lessons | Anything persisted across sessions in the browser is input, not truth. A remembered destination must be re-validated against the *current* principal's role at consumption time — the user who stored it and the user who redeems it can be different people. |

## 5. Stale locally-built Docker images made shipped fixes look absent

| Field | Detail |
|---|---|
| Date | Recurring class through 2026; recorded as a process rule |
| Severity | S3 — no production impact; repeated lost diagnostic time and false defect reports |
| Impact | Fixes that were verifiably on `main` appeared "not working" on the local compose stack (symptoms included wrong flight durations and missing SSO behaviour). The apparent bugs were image skew, not code defects. |
| Detection | Comparing the running containers' image build dates (`docker images` CreatedAt) against `git log` for the fix commits: the images predated the fixes. |
| Root cause | The local compose stack keeps running previously built local images; without an explicit rebuild, `docker compose up` happily serves a codebase from days earlier, and a worktree with WIP builds images that match neither `main` nor the WIP. |
| Fix | Process, not code: the **clean-state rule**, recorded as a recurring root-cause class in the Engineering Handbook (`docs/enterprise/07_ENGINEERING_HANDBOOK.md` §7) and the QA Plan (`docs/enterprise/08_QUALITY_ASSURANCE_PLAN.md` §5) — verify on freshly built images from a clean worktree; check image build metadata before filing a defect. |
| Lessons | "The fix is on main" and "the fix is in the container" are different claims. Before debugging a fix that "didn't take", compare image CreatedAt with the fix commit's date — thirty seconds that has repeatedly saved hours. |

## 6. Mail silently failing: default SMTP was Gmail with placeholder credentials

| Field | Detail |
|---|---|
| Date | 2026-07-25 |
| Severity | S2 — booking confirmations, tickets, and password-reset mail never delivered |
| Impact | On a plain `docker compose up`, every email send failed authentication and was dropped: no booking confirmation, no e-ticket attachment, no password reset. No error surfaced to the user. |
| Detection | Reported as "no booking confirmation email"; traced to notification-service send failures. |
| Root cause | notification-service's `application.yml` defaulted to `smtp.gmail.com:587` with placeholder `MAIL_USERNAME`/`MAIL_PASSWORD`. The Mailpit sink and its `SPRING_MAIL_*` override lived only in `docker-compose.e2e.yml`, so the main compose stack had no working mail path at all. |
| Fix | `6af506d` — Mailpit and the sink wiring moved into the main `docker-compose.yml` (overridable via env for real SMTP). Mail is viewable at `http://localhost:8025` out of the box. Verified: fresh booking confirmation landed in Mailpit with the ticket PDF attached; password-reset and welcome mails delivered. |
| Lessons | A default that silently drops output is worse than one that refuses to start. The working local sink belongs in the default compose file, with real SMTP as the explicit override — not the other way round. |

## 7. `ssh bash -s` stdin drain truncated the staging deploy script

| Field | Detail |
|---|---|
| Date | 2026-08-05 |
| Severity | S2 — release-gate integrity (a vacuous green could have promoted an unverified build) |
| Impact | The first fully-unlocked ladder walk failed at STAGING; debugging found three stacked defects, the worst being a deploy script that could exit 0 with its smoke test silently never run. (The same walk also exposed shared `./docker-data/*` state with prod — staging's Prometheus died on prod's TSDB lock — and a frontend port collision with Loki's 3100, moved to 3900.) |
| Detection | Debugging the failed STAGING rung; the truncation was found by tracing why later script lines never executed. |
| Root cause | `staging-on-vm.sh` travels to the VM over `ssh bash -s`, so **the script's own remaining lines are what's on stdin**. Four `docker exec -i` calls in the seed scripts attached stdin they never read, letting docker's stdin pump swallow the rest of the deploy script — bash then exited 0 at the phantom EOF: a green rehearsal whose smoke test never ran. |
| Fix | `90a8163` — the `-i` flags removed from the seed scripts, and the invoker feeds every stdin-hungry step `< /dev/null` so no future regression can pass vacuously. The mechanism is documented in a comment block in `.github/scripts/staging-on-vm.sh`. Same commit: staging postgres/kafka moved to project-scoped named volumes, observability containers excluded from the rehearsal, frontend port moved off 3100. |
| Lessons | A script delivered over `ssh bash -s` owns its stdin; any child that reads stdin eats the rest of the script. Feed hungry children `/dev/null` explicitly — and treat "the gate passed" with suspicion until you have proof the gate *ran*. |

## 8. Arrival times on the origin's clock — and stale `skybook-common` in images hiding the fix

| Field | Detail |
|---|---|
| Date | 2026-08-04 (fix); origin-clock defect present since flight generation began |
| Severity | S2 — user-facing arrival times wrong by the timezone offset |
| Impact | `arrival_time` was authored on the **origin's** clock throughout, so every displayed arrival was wrong by the zone offset (SQ322 leaves London 21:25; the site said it landed 10:30 — Singapore's arrivals board reads 17:30). Durations *looked* right because the subtraction of two same-clock timestamps gave true flying time — two wrongs compensating. After the fix, local stacks could still show wrong durations: the duration logic lives in `skybook-common` (`AirportTimeZones`), which is baked into the flight, booking, check-in, and notification service images, and stale locally-built images carried the old library (see incident 5). |
| Detection | Investigation of the last open Sonar bug (a duration computed between two zone-naive timestamps) uncovered the larger authoring defect; the image-staleness tail was diagnosed by comparing image build dates against the fix commit. |
| Root cause | Flight generation wrote arrivals on the origin's clock; the frontend subtracted two clocks it could not interpret. Only the server knows the zones. |
| Fix | `1299999` — `AirportTimeZones.elapsedBetween` (in `skybook-common`) became the single place that measures a journey across two zones; flight generation writes arrivals destination-local; flight-service publishes `durationMinutes` and the frontend formats that figure instead of subtracting. Existing rows corrected by `scripts/fix-arrival-times-to-destination-local.sql` (zone-name conversion, DST-correct per date, self-recording so a second run cannot double-shift; 414,679 rows on the local stack). Follow-up `dac6b62` (2026-08-06) pushed destination-local authoring into every seed script after an additive mesh reseed reintroduced origin-clock rows. Consuming service images must be rebuilt for the shared-library change to reach users. |
| Lessons | Two compensating errors can make every visible number look right while both are wrong — verify the primitive facts (the arrival itself), not just the derived ones (the duration). Cross-zone elapsed time belongs in exactly one server-side place. And a shared-library fix only exists for users once every consuming image is rebuilt. |

---

## Recurring root-cause classes

Per the causal-analysis loop in the Engineering Handbook (`docs/enterprise/07_ENGINEERING_HANDBOOK.md` §7), any defect that reached a user triggers the question *which gate should have caught it*, and the answer becomes a new test or a new process rule. Classes recorded so far:

| Class | Incidents | Rule |
|---|---|---|
| Stale local Docker images | 5, 8 | Clean-state rule: verify on freshly built images; check image build metadata against git history first |
| Silent scripted edits (CRLF/multiline misses) | — (see `3678304`) | Positive-grep rule: verify edits by grepping for the new text at every site, never by exit codes |
| API-only verification | 3, 4 | DoD live-verification rule: features are verified against the running system, with named evidence |
| Vacuous gates | 7 | A gate must prove it ran, not merely that nothing failed |
