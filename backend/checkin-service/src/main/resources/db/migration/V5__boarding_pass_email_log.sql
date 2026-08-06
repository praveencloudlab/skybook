-- Boarding-pass email re-sends (GUEST_CHECKIN_MODULE.md §5).
--
-- One row per requested delivery. Doubles as the throttle's shared state
-- (max 3 sends per check-in per hour, counted with a query - correct at any
-- instance count) and as the abuse audit trail: who asked, when, and a hash
-- of where it went. The address itself is NOT stored - the log must be able
-- to prove volume and attribution without becoming a mailing list.

CREATE TABLE IF NOT EXISTS boarding_pass_email_log (
    id            bigserial     PRIMARY KEY,
    check_in_id   bigint        NOT NULL,
    resend_id     varchar(36)   NOT NULL UNIQUE,
    requested_by  varchar(255)  NOT NULL,
    address_hash  varchar(64)   NOT NULL,
    sent_at       timestamp     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_boarding_pass_email_log_checkin_time
    ON boarding_pass_email_log (check_in_id, sent_at);
