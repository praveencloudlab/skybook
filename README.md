# SkyBook

Airline reservations platform — eight Spring Boot microservices behind an API Gateway, backed by PostgreSQL and Kafka. See [`docs/`](docs/) for architecture, per-service design docs, and the [Dockerization design doc](docs/DOCKERIZATION_MODULE.md).

## Run everything locally

Prerequisites: Docker Desktop (Compose v2+; this was verified against Docker 29.5 / Compose v5.1).

```bash
cp env.example .env    # then edit .env - see below
docker compose up --build
```

This starts PostgreSQL (six databases, one per service), a single-node Kafka broker (KRaft, no ZooKeeper), and all eight services. The gateway is reachable at `http://localhost:8080`; every service is also individually reachable on its own port (`8081`-`8087`) for direct testing (e.g. via the Postman collection in `docs/`).

First run takes longer than subsequent ones — Postgres and Kafka both do one-time initialization (creating the six databases, formatting the KRaft log), and Maven's dependency cache is cold. Subsequent `docker compose up --build` runs reuse the BuildKit cache and the bind-mounted data directories.

### `.env`

Copy `env.example` to `.env` (gitignored) and fill in:

| Variable | Required | Notes |
|---|---|---|
| `JWT_SECRET` | Yes | Must be identical across `auth-service` and `api-gateway` — anything else and every token the gateway validates will look forged. |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Yes, for `notification-service` to send email | Gmail SMTP app password, not your normal password. If you don't need email, the service still starts fine; sends will just fail. |
| `CHECKIN_BOARDING_PASS_KEY` | No | Falls back to a dev-only default if unset. |

### Checking on it

```bash
docker compose ps                 # every service should show (healthy)
docker compose logs -f <service>  # tail one service's logs
curl http://localhost:8080/actuator/health   # gateway
```

### Resetting

`docker compose down` stops everything but **keeps your data** (Postgres/Kafka state lives in `./docker-data/`, bind-mounted from the host, not in a Docker-managed volume). To get back to a genuinely clean slate:

```bash
docker compose down
rm -rf ./docker-data
docker compose up --build
```

## Troubleshooting

**A service is stuck `Exited (1)` right after startup, with a Postgres "connection refused" in its logs, only on the *very first* `docker compose up --build` against an empty `./docker-data/`.**
This is a one-time Postgres quirk, not a bug in the app: on a truly first run, the official `postgres` image starts a *temporary* bootstrap server to run the init scripts (creating the six `skybook_*` databases), shuts it down, then starts the real server — a restart cycle that can take several seconds. Every DB-owning service is configured with `SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT=-1` specifically so a service that starts mid-restart retries the connection instead of crashing — if you still see this, just re-run `docker compose up`; the data Postgres already initialized is on disk in `./docker-data/postgres`, so it won't repeat the temp-server dance on the next start.

**`docker compose build` is slow / re-downloads dependencies every time.**
Needs BuildKit (on by default in current Docker Desktop). If it's somehow disabled, `export DOCKER_BUILDKIT=1` before building — the Maven dependency cache mount (`--mount=type=cache,target=/root/.m2`) only works under BuildKit.

**A service can't reach another service (e.g. `booking-service` failing to call `flight-service`).**
Check the `*_BASE_URL` environment variables in `docker-compose.yml` — they must use the other service's Compose *service name* as the hostname (e.g. `http://flight-service:8082`), not `localhost`. `localhost` inside a container refers to that container itself, not its neighbors.

**Windows/Git Bash specifically: a bind mount silently ends up empty, or `docker run`/`docker exec` fails with a mangled path like `C:/Program Files/Git/...`.**
Git Bash auto-converts anything that looks like a POSIX path in command arguments, including inside `-v host:container` mount specs and container-internal paths passed to `docker exec`. Prefix the command with `MSYS_NO_PATHCONV=1` when running `docker` directly from Git Bash. `docker compose` itself isn't affected (paths in `docker-compose.yml` aren't shell arguments), only ad hoc `docker run`/`docker exec` invocations are.
