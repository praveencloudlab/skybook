-- Email verification at registration (OTP over email).
--
-- A new account starts unverified and cannot sign in until it redeems the
-- 6-digit code mailed to it. Accounts that already exist when this migration
-- runs are grandfathered as verified - they proved nothing less than today's
-- accounts did at the time, and locking them all out retroactively would be
-- a self-inflicted outage.

ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified boolean NOT NULL DEFAULT FALSE;

UPDATE users SET email_verified = TRUE;

-- One live code per user (UNIQUE user_id): issuing a new code replaces the
-- old row, so the newest email is the only redeemable one - same doctrine as
-- password_reset_tokens. Only the SHA-256 hash is stored; the 6-digit code
-- itself exists only in the email.
CREATE TABLE IF NOT EXISTS email_verification_otps (
    id            bigserial    PRIMARY KEY,
    user_id       bigint       NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    otp_hash      varchar(64)  NOT NULL,
    expires_at    timestamp    NOT NULL,
    attempts      int          NOT NULL DEFAULT 0,
    last_sent_at  timestamp    NOT NULL,
    created_at    timestamp    NOT NULL DEFAULT now()
);
