# SKB-DOC-00 — SkyBook Engineering Documentation Index

| | |
|---|---|
| **Document ID** | SKB-DOC-00 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Classification** | Internal — Engineering |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Review cycle** | Quarterly, or on any baseline change |

## 1. Purpose

This index is the single entry point to the SkyBook engineering documentation
set. Every controlled document is listed here with its identifier, audience,
and precedence. A document not listed here is not part of the baseline.

## 2. How this documentation set is organised

The set has three layers. **Governance documents** (this folder,
`docs/enterprise/`) define what the system must do, how it is shaped, and how
engineering work is performed. **Module design documents** (`docs/*_MODULE.md`)
are the low-level designs — one per subsystem, written before implementation
and amended with implementation notes after. **Operational documents** cover
deployment, environments, and incident response.

Precedence when documents disagree: the **code and its tests** are the ultimate
authority on behaviour; the **module design document** is the authority on
intent; the governance layer is the authority on scope and process. A
disagreement between layers is a defect in the documentation and must be
resolved, not tolerated (see SKB-DOC-07 §9).

## 3. Controlled documents

### Governance (docs/enterprise/)

| ID | Title | Audience |
|---|---|---|
| SKB-DOC-00 | Document Index (this document) | Everyone |
| SKB-DOC-01 | Software Requirements Specification (SRS) v2 | Everyone |
| SKB-DOC-02 | System Architecture (HLD) | Developers, architects |
| SKB-DOC-03 | Low-Level Design standard & module-doc register | Developers |
| SKB-DOC-04 | Interface Control Document (APIs & events) | Developers, integrators |
| SKB-DOC-05 | Data Architecture | Developers, DBAs |
| SKB-DOC-06 | Security Architecture | Developers, security reviewers |
| SKB-DOC-07 | Engineering Handbook (standards & process) | Developers |
| SKB-DOC-08 | Quality Assurance Plan | Developers, QA |
| SKB-DOC-09 | Release & Environments Policy | Developers, release managers |
| SKB-DOC-10 | Operations Runbook | On-call engineers |
| SKB-DOC-11 | Requirements Traceability Matrix (RTM) | QA, auditors |

### Module low-level designs (docs/)

Registered and change-controlled through SKB-DOC-03 §3. Highlights:
`ROUND_TRIP_MODULE.md`, `SEAT_SELECTION_MODULE.md`,
`SECURITY_HARDENING_MODULE.md`, `OBSERVABILITY_MODULE.md`,
`RESILIENCE_MODULE.md`, `IDEMPOTENCY_MODULE.md`, plus one design per service.

### Operational

| Document | Purpose |
|---|---|
| `docs/ENVIRONMENTS.md` | Environment ladder and the Promote pipeline |
| `docs/DEPLOY_ORACLE.md` | Production VM bootstrap (Oracle Always Free) |
| `docs/DR_RUNBOOK.md` | Disaster-recovery procedures |
| `docs/E2E_CERTIFICATION_MODULE.md` | Release certification suite |
| `docs/TEST_REPORT_PASSENGER_FEATURES.md` | Latest feature verification evidence |

### Superseded

| Document | Superseded by | Note |
|---|---|---|
| `docs/01_SOFTWARE_REQUIREMENT_SPECIFICATION.md` | SKB-DOC-01 | v1-era scope; predates multi-city, through-ticketing, the cancellation policy, and the admin console. Retained for history only. |
| `docs/requirements/01_SRS_v1.md` | SKB-DOC-01 | As above. |

## 4. Document control rules

1. Every governance document carries the control block shown at the top of
   this page. **Version** increments on any normative change; editorial fixes
   (typos, links) do not bump the version but must still go through review.
2. Changes to governance documents ship as ordinary pull requests and require
   one approving review, exactly like code (SKB-DOC-07 §8). The PR description
   must state which requirement, decision, or process changed and why.
3. **Status** values: `Draft` (not yet binding), `Baselined` (binding),
   `Superseded` (kept for history; header must name the successor).
4. Requirement identifiers (SKB-DOC-01) and interface identifiers
   (SKB-DOC-04) are **never reused** after retirement.
5. Each document states facts once and links elsewhere otherwise. If you find
   yourself copying a table between documents, you are creating a future
   inconsistency — link instead.

## 5. Reading paths

- **New developer, first week:** SKB-DOC-01 → SKB-DOC-02 → SKB-DOC-07, then
  the module doc for your first assignment, then SKB-DOC-04 §3 (auth) because
  every endpoint you touch is behind it.
- **Designing a new feature:** SKB-DOC-07 §5 (design-first process) →
  SKB-DOC-03 (LLD template) → SKB-DOC-01 (register the requirements) →
  SKB-DOC-11 (extend traceability).
- **On-call:** SKB-DOC-10, with `docs/DR_RUNBOOK.md` and
  `docs/ENVIRONMENTS.md` at arm's reach.
- **Auditor / assessor:** SKB-DOC-11 traces every requirement to its design,
  implementation, and verification evidence.
