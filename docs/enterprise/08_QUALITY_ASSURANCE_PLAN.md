# SKB-DOC-08 — Quality Assurance Plan

| | |
|---|---|
| **Document ID** | SKB-DOC-08 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |

## 1. Quality objectives (quantitative)

| Objective | Target | Current baseline | Measured by |
|---|---|---|---|
| Line coverage (combined unit+IT) | ≥ 80% overall; ≥ 80% on new code | 91.2% | JaCoCo → SonarCloud |
| Open bugs / vulnerabilities (static) | 0 | 0 | SonarCloud gate |
| Backend suites | 100% green on every merge | green | CI `mvn verify` |
| Frontend suite | 100% green | 54/54 | Vitest |
| Release certification | E2E suite green against a fresh stack | per release | `E2E_CERTIFICATION_MODULE.md` |

The Sonar quality gate is **blocking**: a red gate stops the merge, no
exceptions without a written waiver in the PR approved by the owner.

## 2. Test pyramid (what exists and what each level is for)

| Level | Location / tooling | Purpose & binding examples |
|---|---|---|
| Domain unit | `src/test/.../domain`, plain JUnit+AssertJ | Pure policy classes, **both sides of every boundary** (CancellationPolicyTest tests 71h/73h, not just "before/after") |
| Service unit | Mockito | Transaction-shaped logic, state machines, compensation ordering |
| Web slice | WebMvc tests | Binding, guards, error mapping |
| Persistence | Testcontainers + real PostgreSQL | JPA mappings, migrations, queries against the real dialect |
| Kafka integration | Testcontainers full-stack | Producer→consumer round trips, DLT behaviour |
| Concurrency | Dedicated race tests | Money invariants under contention (exactly-one-refund) |
| Rendered-artefact regression | Real renderer + extractor | The emailed PDF pass rendered through openhtmltopdf and text-asserted with PDFBox; the e-ticket captured-file test asserting £ amounts and coupons |
| Certification E2E | `E2E_CERTIFICATION_MODULE.md` | Whole-stack passenger journeys on a clean deployment |
| Live verification | Manual, evidenced | DoD §4.3 — through the user's actual path, recorded in the test report |

Placement rule: test at the **lowest level that can catch the defect**; a
bug found in E2E that a unit test could have caught requires that unit test
in the fix.

## 3. What must be tested for every change

1. New/changed policy or calculation ⇒ domain units incl. boundary values on
   both sides.
2. New endpoint ⇒ web-slice test for guards (owner, role) + happy path.
3. New event or field ⇒ consumer test incl. duplicate delivery and the
   absent-field (legacy event) case.
4. Bug fix ⇒ regression test that fails without the fix (§2 placement rule),
   or a written reason why no level can express it.
5. Anything touching money ⇒ the arithmetic identity test
   (refund + fees == paid) and, where concurrent, a race test.

## 4. Verification evidence & reporting

Feature rounds produce a test report
(current: `docs/TEST_REPORT_PASSENGER_FEATURES.md`) whose format is binding:
what was tested **against the running system**, per-item concrete evidence
(PNRs, amounts, HTTP statuses), suite results, and an honest
known-limitations section. "Verified" without named evidence is not
verified. The RTM (SKB-DOC-11) links every requirement to its evidence.

## 5. Defect management

Per SKB-DOC-07 §7 (severities, root cause, regression test, causal-analysis
loop). Additionally: any defect that reached a user (rather than being
caught by a gate) triggers the question *which gate should have caught it*,
and the answer becomes either a new test or a new rule in the handbook.
Recorded precedents: API-only verification → DoD live-verification rule;
stale local Docker images → clean-state rule; silent scripted edits →
positive-grep rule.

## 6. Non-functional verification

- **Resilience:** live degradation drills (dependency down, broker down) per
  `RESILIENCE_MODULE.md` §verification; circuit-breaker state visible in
  Grafana.
- **Performance:** the search-pagination regression class is guarded by
  tests; any endpoint returning unbounded collections is a review reject.
- **Security:** posture per SKB-DOC-06; scanner findings triaged in Sonar;
  ownership guards covered by web-slice tests.
- **Observability:** health groups (`/livez`, `/readyz`) asserted in the
  reactor; dashboards are part of the deployment, not an afterthought.

## 7. Release gate summary

A release (merge to main / promote) requires: CI green (build, all suites,
Sonar gate) → certification E2E green on a clean stack → test report updated
→ RTM updated for new requirements → deployment per SKB-DOC-09.
