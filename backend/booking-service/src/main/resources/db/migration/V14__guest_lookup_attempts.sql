-- Failed guest-lookup attempts (GUEST_CHECKIN_MODULE.md §6).
--
-- The per-source limiter at the gateway cannot stop a DISTRIBUTED guess
-- against one booking reference; this table can: booking-service counts the
-- failures per reference in a sliding window (5 per 15 minutes) with a query,
-- which is correct at any instance count because the database is the shared
-- state. Rows are tiny and short-lived; the issuance path prunes entries
-- older than the window opportunistically.

CREATE TABLE IF NOT EXISTS guest_lookup_attempts (
    id                 bigserial    PRIMARY KEY,
    booking_reference  varchar(20)  NOT NULL,
    attempted_at       timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_guest_lookup_attempts_ref_time
    ON guest_lookup_attempts (booking_reference, attempted_at);
