-- Federated identities (SSO_MODULE.md §4.1) - which external identity maps to
-- which SkyBook account.
--
-- The key is (provider, subject) - NEVER email: Google's 'sub' is stable for
-- the life of the Google account, while its email can change. email_at_link is
-- a forensic record of what the address was at link time, not a lookup key.
--
-- users is untouched: users.password was nullable from the V1 baseline, so a
-- Google-only account is simply a row with password = NULL. One identity per
-- provider per account (uq_user_provider); one account per external identity
-- (uq_provider_subject).

CREATE TABLE IF NOT EXISTS federated_identities (
    id             bigserial     PRIMARY KEY,
    user_id        bigint        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider       varchar(20)   NOT NULL,
    subject        varchar(255)  NOT NULL,
    email_at_link  varchar(255)  NOT NULL,
    linked_at      timestamp     NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_user_provider    UNIQUE (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_federated_identities_user_id
    ON federated_identities (user_id);
