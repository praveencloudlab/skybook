# SKB-DOC-07 — Engineering Handbook (Standards & Process)

| | |
|---|---|
| **Document ID** | SKB-DOC-07 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |

This is the how-we-work document. It is short on purpose: rules that are not
followed are worse than no rules, so only the ones we actually enforce are
here.

## 1. Java / Spring standards

1. **Java 21, Spring Boot 3.** Records for DTOs and value objects; Lombok
   only for entities/builders where already established.
2. **Constructor injection only.** `@Value` fields are constructor
   parameters (testability); field injection is rejected in review.
3. **Layering per service:** `controller → facade → service → repository`,
   with `domain/` for pure policy classes (state machines, calculators,
   policies — no I/O, fully unit-testable). Facades own orchestration and
   compensation; services own transactions; controllers own nothing but
   binding and guards.
4. **Transactions end where events begin.** `@Transactional` on service
   methods; facades publish to Kafka only after the service call returned
   (equivalent to after-commit). Never publish inside a transaction.
5. **Money** is `BigDecimal`, rounding HALF_UP scale 2, only inside the
   policy classes. Never `double`, never rounding at the edge.
6. **Time**: schedule times are airport-local `LocalDateTime` by system
   convention; any code comparing them with `LocalDateTime.now()` must note
   the convention (SKB-DOC-01 §5 records the accepted skew).
7. **Errors:** domain rules throw `IllegalStateException`/
   `IllegalArgumentException` with messages written for the passenger or
   agent who will read them ("Bookings close 60 minutes before departure"),
   mapped to 4xx by the shared handler. No error message may lie about the
   cause.
8. **Comments** state constraints the code cannot (the *why*, the invariant,
   the rollout note) — not what the next line does.

## 2. Frontend standards

TypeScript strict; components in `features/<area>/`; API clients only in
`src/api/*` (components never fetch directly). The frontend **renders**
server truth — it may pre-check for UX (disable a button) but every
authoritative number or window is server-computed (SKB-DOC-02 §7.6). All
user-visible strings go through the i18n table; new UI must render sanely in
RTL. State that must survive navigation uses the journey draft
(sessionStorage) pattern.

## 3. Git & change control

- Branches: `feature/<topic>` off `main`; `main` is protected — merges by PR
  only, green CI required. Never push to `origin main` from a feature
  branch.
- Commits: conventional prefix (`feat|fix|test|docs|style|chore(scope):`),
  imperative subject, body explaining *why* and, for fixes, the observed
  failure. **No AI attribution trailers of any kind** (hard project rule).
- One logical change per commit; migrations and their code land together.

## 4. Definition of Done

A change is done when **all** of these hold:

1. LLD written/amended and reviewed (for changes in SKB-DOC-03 §1 scope).
2. Code + tests at the pyramid levels the QA plan requires (SKB-DOC-08 §3).
3. **Verified on the running stack through the user's actual path** — UI
   flows through the UI, not just the API (a standing lesson: four shipped
   bugs once came from API-only verification). Evidence (what was clicked,
   what was observed) goes in the commit or test report.
4. Interfaces/docs updated in the same PR (SKB-DOC-04 §6, SKB-DOC-00 §4).
5. CI green including the Sonar gate; no new warnings silenced without a
   written justification.
6. Verified on **clean state** when the change touches build/CI/seeds —
   local accumulated state has masked defects before (five CI-only defects
   in one release; the rule exists because of them).

## 5. Feature lifecycle

`Idea → LLD (design-by-trace, reviewed) → requirements registered in
SKB-DOC-01 → build in review-sized increments → live verification →
test report/RTM updated → merge → module doc gains Implementation Notes.`
For multi-step features, the LLD's build-order is executed and verified
step-by-step (the round-trip module's 8-step rollout is the reference).

## 6. Scripted-edit discipline

When editing files by script (sed/perl/bulk refactors): **verify by positive
grep for the new text at every site**; exit codes and "patched" logs are not
evidence (CRLF/multiline misses fail silently — documented recurring trap).
Background build chains must be verified by checking the *served artefact*
(bundle hash / marker string), not by the build's exit status.

## 7. Defect handling

Every user-reported defect gets: reproduction, root cause, fix, **a test
that fails without the fix** where the pyramid allows, live verification,
and an entry in the current test report with evidence. Classification:
`S1` money/data corruption (fix before anything else), `S2` user-blocking,
`S3` degraded, `S4` cosmetic. Recurring root-cause classes (stale local
images, silent scripted edits, API-only verification) are recorded as
process rules in this handbook — that loop is our causal-analysis practice:
the fix for the *class*, not just the instance.

## 8. Code & design review

One approving review minimum. Reviewers verify, in order: correctness
against the LLD, failure paths and compensation, security class of any new
endpoint (SKB-DOC-06 §7.1), event-contract compatibility (SKB-DOC-04 §5.4),
tests at the right level, and documentation updated. Review comments that
request style changes contrary to this handbook are resolved by the
handbook, not by debate.

## 9. Documentation upkeep

Docs are code: PR-reviewed, versioned, and subject to the precedence rule in
SKB-DOC-00 §2. If implementation diverges from a design mid-build, the
design is amended *first*, then the code proceeds. Stale documentation is
raised and fixed like a defect.
