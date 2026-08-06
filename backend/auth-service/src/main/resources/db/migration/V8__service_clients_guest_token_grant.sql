-- Guest-token issuance grant (GUEST_CHECKIN_MODULE.md §3.1).
--
-- Minting a browser-facing guest session is a bigger privilege than minting a
-- machine token for yourself, so it is a separate, explicit, default-false
-- grant - not something inferred from allowed_audiences. Only booking-service
-- carries it: it is the one service that can verify a reference + surname
-- against the data before asking for a session.

ALTER TABLE service_clients
    ADD COLUMN IF NOT EXISTS may_issue_guest_tokens boolean NOT NULL DEFAULT false;
