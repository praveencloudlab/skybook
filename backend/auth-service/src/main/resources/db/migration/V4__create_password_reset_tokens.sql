-- Password-reset tokens (FRONTEND_MODULE.md - "Forgot password").
--
-- Only a SHA-256 HASH of the token is stored, never the token itself: the raw
-- value lives only in the email link, so a database leak yields nothing an
-- attacker could redeem. Tokens are single-use (used_at) and short-lived
-- (expires_at); the reset flow deletes any outstanding tokens for a user before
-- issuing a new one, so a fresh request silently invalidates an older link.

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          bigserial    PRIMARY KEY,
    user_id     bigint       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  varchar(64)  NOT NULL UNIQUE,
    expires_at  timestamp    NOT NULL,
    used_at     timestamp,
    created_at  timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id
    ON password_reset_tokens (user_id);
