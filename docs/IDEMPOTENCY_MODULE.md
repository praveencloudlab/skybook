# 🔁 SkyBook Idempotency — Safe Retries on Money-Adjacent Writes — Design

## Project Information

| | |
|---|---|
| **Author** | Praveenreddy Somireddy |
| **Status** | PROPOSED — awaiting review; implementation starts on freeze |
| **Scope** | Booking creation, payment creation/authorize/capture/refund, and the one Kafka path that double-charges on redelivery |
| **Depends on** | SECURITY_HARDENING_MODULE.md (ownership) · the existing payment `Idempotency-Key` precedent |
| **Explicitly out of scope** | The transactional outbox (its own increment) · duplicate *emails* on consumer redelivery (harmless, lands with the outbox) · inventory holds (already money-idempotent, §2.4) |

# 1. Overview

One sentence:

> **A retry must be free.** Any money-adjacent write may be replayed — by a
> double-click, a lost response, or a Kafka redelivery — and the platform
> must answer with the *original outcome*, never a second one.

Today it isn't free. The trace found three distinct failure modes, one of
which takes a customer's money twice, and none of which any test covers.

# 2. Load-Bearing Findings (traced against the live code, not assumed)

## 2.1 Booking creation has no protection at all — and it cascades

`POST /api/bookings` (`BookingController` L45-49) accepts no key and has no
dedupe of any kind. The PNR is *server-generated at random*
(`PnrGenerator`), so nothing about a repeated request looks repeated.

A second identical POST therefore produces a second PNR, a second draft, a
second set of seat holds, a second `BookingPayment`, and a second Kafka
`CREATED` event — and payment-service, whose own dedupe keys on
`bookingId`, sees a *different* booking id and dutifully creates a second
payment. **One retry, two bookings, two charges.**

## 2.2 The frontend cannot currently retry safely — and one dialog invites it

`api/client.ts` has no retry logic (a network failure throws immediately),
so the retries are *human*: the button comes back and the user presses it.

- `PaymentPage.payNow` guards double-clicks with a stage check, but its
  catch resets to `'form'` — so after a lost response the second press
  creates the second booking of §2.1.
- `ModifyBookingDialog.confirmRebook` is worse: no busy guard at the top,
  and its `disabled` omits the busy flag that every sibling screen
  includes. Its retry path runs create → pay → cancel-old again, with the
  first (paid) booking still in hand.

The header hook already exists and is unused: `RequestOptions.headers` is
documented *"e.g. Idempotency-Key on payment creation"* and **no call site
passes it**.

## 2.3 Payment's existing key is the right shape with three holes

`PaymentController` L40-50 + `PaymentServiceImpl` L61-76 implement
key → replay → `200` (vs `201`), stored unique on `payments.idempotency_key`.
Good precedent. The holes:

1. **No request fingerprint.** The same key with a *different body*
   silently returns the first payment. A client bug becomes a silent
   wrong answer.
2. **The race is unhandled.** SELECT-then-INSERT in one transaction with
   no lock: two concurrent replays collide on the unique constraint and
   the loser surfaces a raw `DataIntegrityViolationException` (no handler
   → 500).
3. **Create only.** `authorize`, `capture`, `cancel`, `refund` take no key.

## 2.4 Inventory already solved this, and its shape is the model

`InventoryServiceImpl.holdSeat` L204-209 looks up the passenger's existing
ACTIVE hold *before touching anything* and replays it
(`replayOrConflict`) — same intent replays, different intent 409s, under a
pessimistic flight lock. The comment even calls it "money-idempotency".
This design deliberately mirrors that vocabulary rather than inventing one.

## 2.5 A Kafka redelivery refunds twice — real money, today

`payment-service`'s `BookingEventConsumer` handles `PARTIALLY_CANCELLED`
by calling `paymentFacade.refund(...)` after checking the payment is
`CAPTURED` **or `PARTIALLY_REFUNDED`** (L72, L117). After the first partial
refund the status *stays* `PARTIALLY_REFUNDED` — so a redelivered event
passes the guard and creates a **second `Refund` row and a second gateway
call**. `RefundServiceImpl.beginRefund` has no dedupe whatsoever, and this
consumer *does* rethrow, so the DLT retry policy (3 attempts) makes
redelivery a certainty rather than a theory.

This is the one finding that is a defect *now*, independent of the feature.

## 2.6 The house already has the conventions this needs

- `409` is the universal "your write conflicts with existing state"
  (domain conflicts, `IllegalStateException`, optimistic-lock loss).
- Replay is already expressed as **`200` with the original body**
  (payment create), which sits outside `ErrorResponse` — no new shape.
- `ErrorResponse` has **no machine-readable code field**, so a client
  cannot distinguish conflict flavours programmatically. Stated, not
  changed (§9 D5).
- Every entity carries `@Version` via the shared `Auditable`.
- `CorsConfig` allows only `Authorization` and `Content-Type`; a
  cross-origin `Idempotency-Key` preflight would fail today. Same-origin
  in dev and prod, so it is latent — fixed anyway, one line.

# 3. The Design

## 3.1 The key: who makes it, and when

**The client mints it, once per user intent, and reuses it across retries.**
A key generated per *request* protects nothing.

- `crypto.randomUUID()` at the moment the funnel reaches the pay step,
  stored **with the funnel state** (the same sessionStorage the journey
  already uses), so a page reload or a second press reuses it.
- Cleared when the booking succeeds, or when the user changes what they
  are buying (a different flight is a different intent).
- Sent as `Idempotency-Key` on `POST /api/bookings` and
  `POST /api/payments`.

## 3.2 Server contract (one rule, three services)

For a keyed write:

| Case | Answer |
|---|---|
| No key seen before | Do the work; store `(key, fingerprint, result)`; `201` |
| Same key, same fingerprint, completed | **Replay the stored result, `200`** |
| Same key, same fingerprint, still in flight | `409` "that request is still being processed" |
| Same key, **different** fingerprint | `409` "this key was used for a different request" |
| No key at all | Do the work (backwards compatible, `201`) |

**Fingerprint** = SHA-256 over a canonical projection of the request — the
fields that decide *what is bought* (flight ids, passenger identities,
fare/cabin, contact), never volatile ones. It exists to catch client bugs,
not to be a second identity.

## 3.3 Storage: a column on the aggregate, not a side table

`bookings` gains `idempotency_key` (unique, nullable) and
`idempotency_fingerprint`, exactly like `payments` already has. Two
reasons over a generic `idempotency_records` table: the replayed answer
*is* the aggregate (no serialized copy to keep in sync), and the unique
constraint the DB already enforces per row is precisely the guarantee
needed. The cost — a key is scoped to one endpoint's aggregate — is the
correct scope here.

## 3.4 The race, handled the way this codebase already handles races

Catch `DataIntegrityViolationException` on insert, re-read by key, and
return the winner's row as a replay — the same translate-the-race pattern
`AuthService.register` and `SsoAccountService` use. No new locking.

## 3.5 Authorize / capture / refund: idempotent by STATE, not by key

These already refuse a repeat via the state machine (`validateCapturable`
etc.), which produces a bad client experience: a lost response on a
*successful* capture makes the retry look like an error, and the UI reports
failure on money that moved.

Rather than bolt keys onto them, make the terminal states self-replaying:
**a capture on an already-`CAPTURED` payment returns `200` with the current
payment** (same for authorize on `AUTHORIZED`). The operation's intent is
"be captured"; if it already is, the intent is satisfied. Only a genuinely
contradictory transition (capture on `CANCELLED`) stays `409`.

## 3.6 The Kafka double-refund (§2.5)

Two changes, both small:

1. **A refund carries the reason it exists.** `refunds` gains
   `source_reference` (unique per payment): for a booking-cancellation
   refund that is the booking event's own identity —
   `bookingId + ":" + cancelledPassengerIds` — so a redelivery of the same
   cancellation cannot create a second row. The unique constraint is the
   enforcement; the consumer catches the violation and treats it as
   already-done.
2. **The guard states its real intent.** The `PARTIALLY_REFUNDED` arm
   stops meaning "refund again" and starts meaning "refund the passengers
   not yet refunded", which is what the sentence was always trying to say.

# 4. What Does NOT Change

- Inventory holds (already replay by passenger — §2.4).
- Consumers that are naturally idempotent: booking's payment consumer,
  check-in's booking consumer, booking's check-in consumer (all check
  current state first — verified in the trace).
- Notification's three consumers still send a duplicate email on
  redelivery. Annoying, not dangerous, and properly the outbox
  increment's problem. Stated, not silently ignored.

# 5. Testing Strategy

- **Unit**: fingerprint canonicalisation (same intent → same hash;
  reordered passenger list → same hash; changed flight → different hash);
  the five-case table of §3.2; the race arm (simulated
  `DataIntegrityViolationException` → replay, not 500).
- **Integration**: two concurrent identical POSTs against one database
  produce **one** booking and one payment (the test that would have caught
  §2.1); a capture replay returns 200 with the captured payment.
- **Consumer**: the same `PARTIALLY_CANCELLED` event delivered twice
  produces one refund row and one gateway call — the §2.5 defect, pinned.
- **E2E**: the funnel's own retry — create with a key, replay the exact
  call, assert one PNR and one charge.
- **Frontend**: the key survives a failed attempt and a reload; changing
  flight mints a new one.

# 6. Build Order

1. **payment-service**: fingerprint + race handling on the existing key;
   state-replay for authorize/capture (§3.5) + tests.
2. **payment-service**: `refunds.source_reference` + consumer guard
   (§3.6) + the double-delivery test. *(Standalone defect fix — correct
   with or without the rest.)*
3. **booking-service**: `idempotency_key` + `idempotency_fingerprint`
   (V15), header plumbing, replay/conflict/race + tests.
4. **Gateway**: `Idempotency-Key` in the CORS allowlist.
5. **Frontend**: mint-and-persist per intent, send on both POSTs, clear on
   success/intent change; fix `ModifyBookingDialog`'s missing busy guard
   (§2.2) + tests.
6. Full suites + Sonar; e2e retry journey.
7. Live verification, then the pipeline.

# 7. Decision Log

| # | Decision | Reasoning | Status |
|---|---|---|---|
| D1 | Client mints the key per **intent**, not per request | A per-request key protects nothing; the retry must carry the original | Proposed |
| D2 | Key + **fingerprint**, mismatch → 409 | A key reused with a different body is a client bug; answering it with someone else's result is worse than refusing | Proposed |
| D3 | Column on the aggregate, not a generic records table | The replayed answer IS the aggregate; no serialized copy to drift | Proposed |
| D4 | authorize/capture become **state-idempotent** rather than key-bearing | Their intent is a target state; reporting failure on money that already moved is the worse bug | Proposed |
| D5 | Keep `ErrorResponse` as-is (no machine-readable code) | Out of scope, and the two new 409s are human-actionable; a code field is a platform-wide change deserving its own decision | Proposed |
| D6 | Fix the Kafka double-refund **in this increment** | It is a live money defect the trace surfaced, small, and squarely in scope | Proposed |

# 8. Deferred, Explicitly

- Transactional outbox (exactly-once publication) — next increment.
- Consumer-side email dedupe — lands with the outbox.
- A generic idempotency layer (filter/interceptor) for every POST — the
  three money paths are the ones that matter; a platform-wide mechanism
  without a platform-wide need is speculative.
- Machine-readable error codes in `ErrorResponse` (D5).
