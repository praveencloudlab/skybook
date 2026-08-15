# SKB-DOC-03 — Low-Level Design Standard & Module Register

| | |
|---|---|
| **Document ID** | SKB-DOC-03 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |

## 1. The rule: design before code

No feature that crosses a service boundary, adds a schema migration, touches
money, or changes an event contract may be implemented without a low-level
design document (or an amendment to an existing one) **reviewed before the
first line of production code**. Trivial changes (copy, styling, a bug fix
inside one method) are exempt; when unsure, it is not exempt.

The house design discipline is **design by trace**: before a design is
presented for review it must trace the real runtime flow end to end — actual
enum values, DTO shapes, constraints, and failure paths from the current
code, not from memory — and include a self-audit against those. Designs that
name a method that does not exist, or a state that cannot occur, are
returned without review.

## 2. LLD template

Every module design document follows this skeleton (see
`ROUND_TRIP_MODULE.md` for the reference example):

```
# <MODULE> — Design
1. Problem & scope            what changes for the user; what is out of scope
2. Current-state trace        the relevant existing flows, from code
3. Domain model               entities, states + LEGAL transitions, invariants
4. Data changes               Flyway migrations, new columns/tables, backfills
5. Flows                      step-by-step, including EVERY failure path and
                              its compensation
6. Interface changes          endpoints (SKB-DOC-04 register), event fields,
                              consumer impact
7. Decisions                  each with the rejected alternative and why
8. Test plan                  which pyramid levels, which named cases
9. Rollout                    ordering constraints, feature flags, deprecations
10. Implementation notes      appended AFTER build: what differed and why
```

Section 10 is mandatory and is what keeps designs honest: an LLD whose
implementation notes say "as designed" for a module that visibly differs is
a documentation defect (SKB-DOC-00 §2).

## 3. Module design register

Binding low-level designs, in dependency order. Each is change-controlled by
PR like all documentation.

| Module document | Governs |
|---|---|
| `API_GATEWAY_MODULE.md` | Edge routing, token verification, public paths |
| `SECURITY_HARDENING_MODULE.md` | Token model, service credentials, ownership (design frozen; see SKB-DOC-06 for the as-built) |
| `FLIGHT_SCHEDULING_MODULE.md` | Schedule generation, fleet, terminals |
| `INVENTORY_SERVICE_MODULE.md` | Seat state machine, holds, pricing tiers |
| `BOOKING_SERVICE_MODULE.md` + `BOOKING_SERVICE_REFERENCE.md` | Core booking domain |
| `SEAT_SELECTION_MODULE.md` | Draft→hold→finalize saga, surcharges |
| `ROUND_TRIP_MODULE.md` | Segments, tickets/coupons, cancellation matrix, through-ticketing |
| `PAYMENT_SERVICE_MODULE.md` | Payment lifecycle, refunds, invoices |
| `IDEMPOTENCY_MODULE.md` | Idempotency keys on booking and payment |
| `CHECKIN_SERVICE_MODULE.md` | Check-in windows, passes, sweeps, baggage |
| `GUEST_CHECKIN_MODULE.md` | Guest sessions and their security posture |
| `NOTIFICATION_SERVICE_MODULE.md` | Templates, attachments, event handling |
| `SSO_MODULE.md` | Google sign-in |
| `FRONTEND_MODULE.md` | SPA structure, screens, i18n |
| `OBSERVABILITY_MODULE.md` | Metrics/logs/traces pipeline |
| `RESILIENCE_MODULE.md` | Timeouts, breakers, bulkheads, DLTs |
| `DOCKERIZATION_MODULE.md` / `CI_CD_MODULE.md` | Build & pipeline |
| `E2E_CERTIFICATION_MODULE.md` | Release certification suite |

## 4. Design review

Reviews follow the process in SKB-DOC-07 §8 with two additions specific to
designs: the reviewer must be able to reproduce the current-state trace
(section 2) from the code, and every state machine in section 3 must
enumerate the transitions it *rejects*, not only the ones it allows —
history shows the rejected-transition list is where the bugs hide.
