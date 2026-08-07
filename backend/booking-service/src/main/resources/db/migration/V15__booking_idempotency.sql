-- Booking-creation idempotency (IDEMPOTENCY_MODULE.md §3.3).
--
-- POST /api/bookings had no protection at all, and the PNR is generated
-- randomly server-side, so nothing about a repeat request looked like a
-- repeat. A retry after a lost response - the browser's network error, the
-- user's second press - produced a SECOND booking, second seat holds, second
-- payment; payment-service dedupes on bookingId, which differed, so it charged
-- twice. This is the create-side companion to the payment fingerprint (V3 in
-- payment-service).
--
-- The key lives on the aggregate, not a side table: the replayed answer IS the
-- booking, so there is no serialized copy to keep in sync, and the DB unique
-- constraint the row already enforces is exactly the once-only guarantee
-- needed. Nullable: a booking made by any path that sends no key (the e2e
-- suite, scripts, older clients) is unaffected.

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS idempotency_key varchar(64);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS idempotency_fingerprint varchar(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bookings_idempotency_key
    ON bookings (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
