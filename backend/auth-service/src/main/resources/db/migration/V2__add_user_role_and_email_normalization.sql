-- Roles + email normalization (SECURITY_HARDENING_MODULE.md §4.1/§4.3/§6).
-- ddl-auto never runs data transforms and Postgres rejects adding a NOT NULL
-- column to a populated table, so this is explicit: add nullable -> backfill
-- -> SET NOT NULL, and normalize email only after proving no rows collide.

-- 1. role: add nullable, backfill every existing user to USER, then lock down.
ALTER TABLE users ADD COLUMN role varchar(20);

UPDATE users SET role = 'USER' WHERE role IS NULL;

ALTER TABLE users
    ALTER COLUMN role SET NOT NULL,
    ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));

-- 2. Email collision guard (review 2): abort loudly if two accounts would
--    collapse to the same normalized email, rather than silently merging or
--    deleting a user. A human resolves the duplicates, then re-runs.
DO $$
DECLARE
    collisions int;
BEGIN
    SELECT count(*) INTO collisions FROM (
        SELECT lower(trim(email)) AS e FROM users GROUP BY lower(trim(email)) HAVING count(*) > 1
    ) dup;
    IF collisions > 0 THEN
        RAISE EXCEPTION 'Email normalization aborted: % email(s) collide when lower/trimmed. Resolve the duplicate accounts, then re-run.', collisions;
    END IF;
END $$;

-- 3. Normalize, then enforce it at the storage layer so a direct DB write
--    cannot bypass application-side normalization.
UPDATE users SET email = lower(trim(email)) WHERE email <> lower(trim(email));

ALTER TABLE users
    ADD CONSTRAINT users_email_normalized_check CHECK (email = lower(trim(email)));
