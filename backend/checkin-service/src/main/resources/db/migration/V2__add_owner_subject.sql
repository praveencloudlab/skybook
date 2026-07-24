-- Ownership snapshot (SECURITY_HARDENING_MODULE.md §4.2): check-in-service
-- captures the booking owner's JWT subject from the CONFIRMED event so it can
-- enforce object-level ownership on its own row. Nullable - legacy rows stay
-- null and are ADMIN/SERVICE-only.

ALTER TABLE check_ins
    ADD COLUMN IF NOT EXISTS owner_subject varchar(255);
