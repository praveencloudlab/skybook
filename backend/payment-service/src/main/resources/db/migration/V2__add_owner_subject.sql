-- Ownership snapshot (SECURITY_HARDENING_MODULE.md §4.2): payment-service
-- captures the booking owner's JWT subject from the BookingCreated event so it
-- can enforce object-level ownership on its own row. Nullable - legacy rows
-- stay null and are ADMIN/SERVICE-only.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS owner_subject varchar(255);
