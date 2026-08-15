# SKB-DOC-05 — Data Architecture

| | |
|---|---|
| **Document ID** | SKB-DOC-05 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |

## 1. Topology

One PostgreSQL server, one **database per service**, initialised by the
multi-database init script at first container start:

| Database | Service | Core aggregates |
|---|---|---|
| `skybook_auth` | auth-service | users (roles, preferences), service clients, reset tokens, SSO links |
| `skybook_flight` | flight-service | airlines, aircraft (+cabin configs), flights (~440k rows, terminals), schedules |
| `skybook_inventory` | inventory-service | seat maps, seat state, holds (TTL), reservations, seat pricing tiers |
| `skybook_booking` | booking-service | bookings, booking_segments, booking_passengers, passengers, tickets, ticket_coupons, booking_payments (mirror), fare_alerts, history |
| `skybook_payment` | payment-service | payments, refunds, invoices, payment history |
| `skybook_checkin` | checkin-service | check_ins, boarding_passes, baggage, manifests, history |

## 2. Non-negotiable rules

1. **No cross-database access.** A service reads and writes its own database
   only. Cross-service data needs are met by events (read-models) or
   synchronous APIs — never by a foreign JDBC URL, view, or FDW.
2. **IDs cross boundaries, rows do not.** Other services store foreign
   *identifiers* (bookingId, flightId, bookingPassengerId) plus the snapshot
   fields they were given by event or API at the time — denormalisation by
   snapshot is the intended pattern (e.g. check-in snapshots flight facts
   and terminals; payment snapshots the fare breakdown).
3. **Single writer** (SKB-DOC-02 rule 1). Read-models (like
   `BookingPassenger.checkInStatus`/seat mirrored from check-in events) are
   updated only by their designated consumer and treated as eventually
   consistent — guards that depend on them must state so in their LLD.

## 3. State machines (binding value sets)

The enum values below are wire- and database-contract. Additions follow
SKB-DOC-04 §5.4; renames are forbidden.

| Aggregate | States | Enforced by |
|---|---|---|
| Booking | DRAFT → CREATED → CONFIRMED → COMPLETED; → CANCELLED; derived PARTIALLY_CANCELLED | `BookingStateMachine` (illegal transitions throw) |
| Payment (payment-svc) | PENDING → AUTHORIZED → CAPTURED → PARTIALLY_REFUNDED → REFUNDED; AUTHORIZATION_FAILED, CAPTURE_FAILED, CANCELLED | `PaymentValidator` + state machine; `refundedAmount ≤ capturedAmount` under `@Version` |
| Payment mirror (booking-svc) | PENDING, PAID, REFUNDED (+stays PAID on forfeiture) | Booking state machine |
| Check-in | NOT_OPEN → OPEN → CHECKED_IN → BOARDED; NO_SHOW; CANCELLED; CLOSED | `CheckInStateMachine`; terminal states have no exits |
| Ticket | ISSUED → REFUNDED / CANCELLED | Coupon-derived |
| Coupon | OPEN → CHECKED_IN → FLOWN; → CANCELLED / REFUNDED | FLOWN is immutable history |
| Seat | AVAILABLE → HELD (TTL) → RESERVED → released | Inventory, atomically |

## 4. Schema change policy (Flyway)

1. Migrations are **append-only**: a merged `V<n>__*.sql` is never edited;
   corrections are a new version.
2. Every migration is **backward-compatible for one release** with the code
   that preceded it (add-then-migrate-then-remove), because compose updates
   are rolling per service.
3. Destructive steps (column drops) ship only after their mirror release
   (SKB-DOC-04 §5.4.3); each names the LLD section that scheduled it
   (current example: booking V-next dropping `bookings.flight_id`, scheduled
   by ROUND_TRIP step 8).
4. Data backfills live in the migration when deterministic (e.g. checkin
   V3/V4 terminal backfills via the SQL twin of `TerminalPolicy`), otherwise
   in an idempotent seed/ops script under `scripts/`.
5. Booking-service's V1 is a **squashed baseline** (round-4 shape) — the
   precedent for future squashes: only at a major version, only with every
   environment at head.

## 5. Reference & seed data

`scripts/seed/` owns non-transactional data and is **idempotent end to end**:
airlines and fleet (including the modern types A350-1000/787-9/A321XLR with
cabin configs), a year of schedule (full-mesh variant ≈ 3×/day/pair),
terminal assignments (`08_terminals.sql` — SQL twin of `TerminalPolicy`,
plus re-fleeting that only touches flights without sold inventory). Seeders
author times **destination-local**; `durationMinutes` is part of the flight
contract. Never run blanket time-fix scripts after additive seeds
(regression documented in the seed README).

## 6. Data integrity & audit

- Money-bearing rows carry `@Version` optimistic locks; concurrent refund
  races resolve to exactly one winner (verified by `PaymentConcurrencyTest`).
- Monetary types are `NUMERIC`/`BigDecimal`, never floats; rounding HALF_UP
  scale 2, applied only in the policy classes (`FareCalculator`,
  `CancellationPolicy`, `RefundCalculator`).
- State transitions on bookings, payments, and check-ins append history rows
  (actor, source, correlation id, note) — NFR-05. History is append-only.
- Invoices are immutable after capture; refunds reference them, never edit
  them.
- PNRs: 6 chars, generated with collision retry (bounded attempts, then
  fail). Ticket numbers are deterministic (`125` + booking id + traveller
  index) so replays cannot double-issue.

## 7. Retention & privacy

Passenger PII (names, passport data, contacts) lives in `skybook_booking`
(travellers) and `skybook_checkin` (snapshots). The platform stores no card
data anywhere — the simulated gateway deals in references only. Backup and
restore procedure, including per-database dumps and the restore order:
`docs/DR_RUNBOOK.md`.
