-- Account-level preferences (passenger features): the language and currency
-- the user chose become account facts, applied on every sign-in on any
-- device - not just a browser-local setting.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS preferred_language varchar(5),
    ADD COLUMN IF NOT EXISTS preferred_currency varchar(3);
