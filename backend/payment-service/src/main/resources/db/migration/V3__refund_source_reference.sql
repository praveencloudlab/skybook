-- Refund de-duplication (IDEMPOTENCY_MODULE.md §3.6).
--
-- A refund had no natural key, so nothing stopped the same cause producing two
-- of them. That is not theoretical: payment-service's BookingEventConsumer
-- refunds on PARTIALLY_CANCELLED after checking the payment is CAPTURED **or
-- PARTIALLY_REFUNDED** - and the first partial refund leaves the payment in
-- exactly PARTIALLY_REFUNDED. A redelivered event therefore passed the guard
-- and issued a SECOND refund and a second gateway call. That consumer rethrows,
-- so the DLT retry policy makes redelivery routine rather than rare.
--
-- source_reference names the CAUSE of the refund ("what am I refunding?"),
-- unique per payment. The consumer derives it from the event, so a redelivery
-- computes the same value and the unique index refuses the duplicate - the
-- database, not a status check, is what enforces once-only. Nullable because
-- refunds raised by hand (admin desk) have no event behind them.

ALTER TABLE refunds ADD COLUMN IF NOT EXISTS source_reference varchar(120);

-- Companion (IDEMPOTENCY_MODULE.md §3.2): the request fingerprint stored
-- beside the existing idempotency_key, so a replayed key can be checked
-- against WHAT was originally requested, not just THAT something was.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_fingerprint varchar(64);

-- Partial index: many manual refunds may have NULL, but a named cause happens
-- at most once per payment.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_payment_source
    ON refunds (payment_id, source_reference)
    WHERE source_reference IS NOT NULL;
