-- Runs once, only when the postgres container's data directory is first
-- initialized (docker-entrypoint-initdb.d convention). Every SkyBook
-- service owns its own database on this one Postgres instance (confirmed
-- from each service's application.yml datasource.url) and relies on
-- Hibernate's ddl-auto: update to create its own tables - no Flyway
-- migrations exist in this repo today (DOCKERIZATION_MODULE.md finding
-- #2.3), so this script only needs to create empty databases.

CREATE DATABASE skybook_auth;
CREATE DATABASE skybook_flight;
CREATE DATABASE skybook_booking;
CREATE DATABASE skybook_inventory;
CREATE DATABASE skybook_payment;
CREATE DATABASE skybook_checkin;
