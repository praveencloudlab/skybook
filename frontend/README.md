# SkyBook Frontend

Passenger-facing web client for the SkyBook platform. Design and decisions live
in [`docs/FRONTEND_MODULE.md`](../docs/FRONTEND_MODULE.md).

React + Vite + TypeScript + Tailwind. It talks to the **API gateway and nothing
else** — individual services are not host-published, which is the correct trust
boundary anyway.

## Running it

The backend must be up first (from the repo root):

```bash
docker compose up -d
```

Then:

```bash
npm install
npm run dev
```

The dev server binds **5173** with `strictPort`, because the gateway's CORS
allow-list already contains `http://localhost:5173`. If the port is taken, Vite
fails loudly rather than sliding to 5174 and being blocked by CORS — a confusing
thing to debug from the browser side.

Point it elsewhere with `VITE_API_BASE_URL` (see `.env.example`); unset, it
defaults to `http://localhost:8080`.

## Scripts

| Command | What it does |
|---|---|
| `npm run dev` | Dev server on 5173 |
| `npm run build` | Type-check, then production build |
| `npm run typecheck` | Types only, no emit |
| `npm run lint` | oxlint |

## Layout

Vertical slices, not layer folders — the journey is the product, so a feature
owns its screens, hooks and types.

```
src/api/         one typed client per service surface; the ONLY place fetch() appears
src/features/    auth · search · booking · payment · checkin
src/components/  shared presentational pieces (SeatMap, FlightCard, Money…)
src/lib/         config, auth storage, polling, error mapping
```

## Two things that will bite you

Both are documented at length in the design doc, and both were found by running
the real platform rather than by reading its code:

1. **`POST /api/auth/login` returns a raw JWT string, not JSON.**
   `response.json()` throws. It has already caught out a Postman script and the
   e2e suite.
2. **Three journey steps complete asynchronously over Kafka** — the payment row,
   the CONFIRMED booking, and the check-in records genuinely do not exist when
   the previous call returns. They must be polled with backoff, not awaited as if
   they were synchronous, or the UI looks broken exactly when the user has just
   paid.
