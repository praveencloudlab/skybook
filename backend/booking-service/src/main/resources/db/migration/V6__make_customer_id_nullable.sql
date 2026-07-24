-- customer_id becomes optional (FRONTEND_MODULE.md §10.3).
--
-- It was NOT NULL since the V1 baseline, which forced every API client to invent
-- a meaningless number: ownership is carried by owner_subject (captured from the
-- authenticated principal, added in V5), and nothing authorizes or looks up by
-- customer_id -- findByCustomerId exists in the repository but is exposed on no
-- endpoint. Dropping @NotNull from the DTO alone was not enough; the column
-- itself rejected the insert, which is what this migration fixes.
--
-- Note it could not simply be *derived* server-side instead: the JWT carries
-- sub (the email), roles and token_type -- there is no numeric user id to derive
-- from, and adding one would change the frozen security module's token shape.
--
-- Existing rows keep their values; the column stays for legacy/back-office use.

ALTER TABLE bookings
    ALTER COLUMN customer_id DROP NOT NULL;
