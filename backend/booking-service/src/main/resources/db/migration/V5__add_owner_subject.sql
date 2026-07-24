-- Ownership (SECURITY_HARDENING_MODULE.md §4.2): the authenticated JWT subject
-- captured at booking creation, so a USER may act only on their own bookings.
-- Nullable - legacy rows stay null and are ADMIN/SERVICE-only.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS owner_subject varchar(255);
