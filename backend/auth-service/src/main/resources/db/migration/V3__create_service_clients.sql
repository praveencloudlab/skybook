-- Service-client registry (SECURITY_HARDENING_MODULE.md §3.3/§4.5): the
-- machine callers that may request a ROLE_SERVICE token. Secrets are stored as
-- BCrypt hashes, never plaintext; rows are provisioned at startup from deploy
-- properties (ServiceClientBootstrap), NOT committed in this migration.

CREATE TABLE IF NOT EXISTS service_clients (
    client_id         varchar(100) PRIMARY KEY,
    secret_hash       varchar(100) NOT NULL,
    allowed_audiences varchar(500) NOT NULL,
    enabled           boolean      NOT NULL DEFAULT true,
    created_at        timestamp    NOT NULL DEFAULT now(),
    updated_at        timestamp    NOT NULL DEFAULT now()
);
