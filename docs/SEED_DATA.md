# 🌱 SkyBook Seed Data

Sample data that makes the running platform **bookable end to end for a full
year** — every route has a departure on every date from **2026-07-14** through
**2027-07-13**, each with real inventory and a seat map behind it.

This data lives in the Docker Postgres volume (`./docker-data/postgres`). It
survives `docker compose down` but is wiped by `docker compose down -v` or by
deleting the volume directory — re-seed with [`scripts/seed/seed.sh`](../scripts/seed/seed.sh).

---

## What's in it

| | Count | Notes |
|---|---:|---|
| **Flights** (`skybook_flight.flights`) | 10,950 | 30 routes × 365 daily departures |
| **Flight inventory** (`skybook_inventory.flight_inventory`) | 10,950 | one sellable-seat record per flight |
| **Aircraft** (`skybook_inventory.aircraft`) | 2 | one narrowbody, one widebody |
| **Aircraft seats** (`skybook_inventory.aircraft_seats`) | 480 | 180 + 300 seat maps |
| **Sellable seats** (sum of `available_seats`) | ~3,022,200 | across all flights |
| **Date span** | 2026-07-14 → 2027-07-13 | "today" + 365 days |

### Files

| File | What it is |
|---|---|
| [`scripts/seed/flights.json`](../scripts/seed/flights.json) | All 10,950 flights as a JSON array (id, flightNumber, airlineCode, origin, destination, departure/arrival, status) |
| [`scripts/seed/routes.json`](../scripts/seed/routes.json) | The 30 route definitions (the non-repeating essence) |
| [`scripts/seed/01_flights.sql`](../scripts/seed/01_flights.sql) | Regenerates the flights from route templates |
| [`scripts/seed/02_aircraft_seats.sql`](../scripts/seed/02_aircraft_seats.sql) | The fleet + seat maps |
| [`scripts/seed/03_flight_inventory.sql`](../scripts/seed/03_flight_inventory.sql) | One inventory record per flight |
| [`scripts/seed/seed.sh`](../scripts/seed/seed.sh) | Runs all of the above in order (one command) |

---

## Routes

30 flight numbers across 18 airlines, all departing UK airports. Each operates
**daily** for the whole year at a fixed local departure time.

| Airline | Route | Example flights |
|---|---|---|
| BA | LHR→JFK, LHR→BOM, LHR→HKG, LHR→JNB, LHR→NBO | BA178, BA117, BA035, BA075, ... |
| VS | LHR→JFK, MAN→ATL | VS003, VS103 |
| AI | LHR→DEL, LHR→BOM, BHX→DEL, GLA→DEL | AI131, ... |
| EK | LHR→DXB, MAN→DXB, BHX→DXB, GLA→DXB | EK001, EK007, ... |
| QR | LHR→DOH, EDI→DOH | QR013, QR003 |
| EY | LHR→AUH, MAN→AUH | EY012, EY020 |
| SQ | LHR→SIN, MAN→SIN | SQ305, SQ325 |
| QF | LHR→SYD | QF002 |
| CX | LHR→HKG | CX250 |
| LH | LHR→FRA, MAN→FRA | LH901, LH908 |
| AF | LHR→CDG, EDI→CDG | AF1081, ... |
| TK | LHR→IST, EDI→IST | TK1980, ... |

The complete, exact list (with departure/arrival times) is in
[`routes.json`](../scripts/seed/routes.json).

## Fleet & seat maps

Each flight's inventory is backed by one of two aircraft, chosen by route type:

| Registration | Model | Seats | Used for | Cabins |
|---|---|---:|---|---|
| `G-SKYA` | Airbus A320neo | 180 | short-haul (CDG, FRA, IST) | rows 1–3 Business, 4–30 Economy |
| `G-SKYB` | Boeing 777-300ER | 300 | long-haul (everything else) | rows 1–2 First, 3–8 Business, 9–14 Premium, 15–50 Economy |

Seats are `<row><letter>` with letters A–F (A/F window, B/E middle, C/D aisle).
So valid seat numbers are **1A–30F** on short-haul and **1A–50F** on long-haul.
Booking currently requires an explicit `seatNumber` per passenger — automatic
assignment isn't implemented yet.

---

## Using it

With ~11,000 flights, use the **search** endpoint (not list-all) through the
gateway at `http://localhost:8080`:

```bash
# 1. get a token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"me@test.com","password":"Passw0rd!23"}')   # register first if needed

# 2. search a route on any date in range
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/flights/search?originAirportCode=LHR&destinationAirportCode=JFK&departureDate=2026-10-15"

# 3. book a flight (seatNumber required; 1A-50F on the 777)
curl -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "customerId": 1,
    "flightId": <id from search>,
    "contact": {"contactName":"Me","contactEmail":"me@test.com"},
    "passengers": [{
      "firstName":"Alice","lastName":"Traveller","dob":"1990-05-20",
      "nationality":"GBR","passportNumber":"AB1234567","passportExpiry":"2030-01-01",
      "travelClass":"ECONOMY","fareType":"FLEXI","seatNumber":"12A"
    }]
  }'
```

A successful booking returns `bookingStatus: CREATED` with a `PENDING` payment,
and decrements the flight's `available_seats` (holding the chosen seat).

## Regenerating

After a `docker compose down -v` (or any time you want a clean set), with the
stack running:

```bash
bash scripts/seed/seed.sh
```

This replaces the flights and inventory tables and re-derives everything —
about 11k flights and 11k inventory rows in a couple of seconds.

---

### India domestic network (seed_india.sh)

Every Indian city with scheduled commercial service — 71 airports, 540 daily
departures, a year from today (~198k flights). Generated from ONE table in
`scripts/seed/gen_india_network.mjs` (edit that, re-run it, never the SQL):

```bash
node scripts/seed/gen_india_network.mjs   # regenerate 12/13/14_*.sql
bash scripts/seed/seed_india.sh           # additive + idempotent
```

Carriers are 6E/AI/IX/QP/SG with realistic flight numbers, hub-and-spoke
(metro trunks 3x daily, every other city to its nearest metros). Cabins are
HONEST per hull: ATR 72s and every LCC narrow-body are all-economy; the only
domestic Business is Air India's two-cabin A320neo on metro trunks — so a
regional hop can never sell a cabin the aircraft doesn't have. The same
airports table patches `AirportTimeZones`, notification's `AirportCityLookup`
and the frontend `AIRPORTS` directory (all Asia/Kolkata — the UTC-fallback
duration gotcha). `refleet.sh` deliberately skips `data-seed-india` flights so
re-fleeting never puts a wide-body back on a spoke. `seed.sh` runs it last.

---

### UK domestic + Crown Dependency network (seed_uk.sh)

Built from the CAA June 2026 Table 12.2 route observations (real demand
weights) and a verified carrier/cabin reference — 47 airports, 128 routes,
612 daily departures with frequencies DERIVED from monthly passenger
volumes. Generated from one route table in `scripts/seed/gen_uk_network.mjs`:

```bash
node scripts/seed/gen_uk_network.mjs   # regenerate 15/16/17_*.sql
bash scripts/seed/seed_uk.sh           # additive + idempotent
```

Cabin truth: BA's two-class A320 (Club Europe 12J+150Y) and CityFlyer E190
(12J+88Y) are the ONLY UK domestic Business; easyJet/Loganair/Emerald/
Ryanair UK/Aurigny/Skybus are all-economy, down to the 8-seat Islander on
the Orkney inter-island PSO hops and the Twin Otters for Barra and Scilly.
Verified corrections baked in: Gatwick–Glasgow is BA-coded but flown by an
Emerald ATR 72 (NO Club Europe); Eastern Airways (ceased 27 Oct 2025) is
absent. Route effective windows are honoured: Loganair Heathrow–Dundee ends
23 Oct 2026 (Dundee base closure), Skybus Newquay–Scilly resumes 1 Sep 2026.
Crown Dependency airports (Jersey/Guernsey/Alderney/IoM) are seeded as
airports; they are not UK territory. `refleet.sh` skips `data-seed-uk`
flights. `seed.sh` runs it after the India network.

---

### How this data got here (context)

The original flights were created earlier against a **native Windows Postgres**
(`postgresql-x64-18`), a separate instance from the Docker Postgres container the
services actually query. The seed scripts here rebuild the data directly in the
Docker database, spread across the next 12 months so any date is bookable.
