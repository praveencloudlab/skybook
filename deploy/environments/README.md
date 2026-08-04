# Environment configuration

One file per rung of the ladder (ENVIRONMENTS.md has the full map). Each file
is passed with `--env-file`, which REPLACES the default `./.env` - so every
variable the compose file requires must be present here or exported.

Secrets are never committed. The ephemeral environments (dev/sit/qa/perf and
the DR drill) generate throwaway secrets per run in the pipeline, exactly as
the nightly certification always has. The standing environments (staging,
prod) read their secrets from the VM's own `.env`, which never leaves it.

LOCAL has no file on purpose: it is the developer default of docker-compose.yml
plus your own `./.env`, with `--build` for the inner loop.
