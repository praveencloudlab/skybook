/**
 * PERF rung of the environment ladder (docs/ENVIRONMENTS.md §PERF).
 *
 * Models the traffic the platform actually serves: overwhelmingly anonymous
 * shopping (search, calendar, quote) with a thin stream of authenticated
 * account activity. Thresholds are the gate - a promotion FAILS if they
 * breach, which is what makes this an environment rather than a chart.
 *
 * Run locally:
 *   docker run --rm -i --network skybook_default \
 *     -e BASE=http://api-gateway:8080 grafana/k6 run - < perf/k6/journey.js
 *
 * The numbers are calibrated to the free-tier reality this platform ships on
 * (4 shared OCPUs carrying 17 containers), and the calibration is honest: the
 * p95 budgets below were set from a measured local baseline, not aspiration.
 * A regression is a promotion stopper; a Ferrari lap time was never the bar.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';

// A spread of real seeded routes so the cache cannot flatter the numbers.
const ROUTES = [
  ['LHR', 'DXB'], ['LHR', 'JFK'], ['DEL', 'LHR'], ['SIN', 'LHR'],
  ['DXB', 'MAN'], ['CDG', 'DXB'], ['ATL', 'CDG'], ['BOM', 'LHR'],
];

function dateDaysOut(days) {
  const d = new Date(Date.now() + days * 86_400_000);
  return d.toISOString().slice(0, 10);
}

export const options = {
  scenarios: {
    // The shop window: sustained anonymous browsing.
    browse: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 15 },   // ramp
        { duration: '2m', target: 15 },    // hold
        { duration: '15s', target: 0 },    // drain
      ],
    },
    // The account stream: register + login + identity probe.
    account: {
      executor: 'constant-vus',
      exec: 'account',
      vus: 3,
      duration: '2m45s',
    },
  },
  thresholds: {
    // The gate. http_req_failed counts 4xx-as-configured/5xx and transport
    // errors; expected 401s below are marked as expected responses.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:search}': ['p(95)<1500'],
    'http_req_duration{kind:calendar}': ['p(95)<1200'],
    'http_req_duration{kind:quote}': ['p(95)<1200'],
    'http_req_duration{kind:auth}': ['p(95)<2500'],   // BCrypt is meant to be slow
  },
};

export function browse() {
  const [origin, destination] = ROUTES[Math.floor(Math.random() * ROUTES.length)];
  const date = dateDaysOut(3 + Math.floor(Math.random() * 60));

  const search = http.get(
    `${BASE}/api/flights/itineraries?originAirportCode=${origin}&destinationAirportCode=${destination}&departureDate=${date}`,
    { tags: { kind: 'search' } },
  );
  check(search, { 'search 200': (r) => r.status === 200 });

  const flights = search.json();
  if (Array.isArray(flights) && flights.length > 0) {
    // Quote is a POST with a body - the same call the fares page makes.
    const flightId = flights[0].legs[0].id;
    const quote = http.post(`${BASE}/api/bookings/quote`,
      JSON.stringify({ flightId }),
      { headers: { 'Content-Type': 'application/json' }, tags: { kind: 'quote' } });
    check(quote, { 'quote 200': (r) => r.status === 200 });
  }

  const cal = http.get(
    `${BASE}/api/flights/calendar?originAirportCode=${origin}&destinationAirportCode=${destination}`
    + `&startDate=${dateDaysOut(1)}&endDate=${dateDaysOut(31)}`,
    { tags: { kind: 'calendar' } },
  );
  check(cal, { 'calendar 200': (r) => r.status === 200 });

  sleep(1 + Math.random() * 2); // think time - humans read result pages
}

export function account() {
  const who = `perf-${__VU}-${__ITER}-${Date.now()}@skybook.perf`;
  const pw = 'PerfLadder#2026x';

  const reg = http.post(`${BASE}/api/auth/register`,
    JSON.stringify({ fullName: 'Perf Ladder', email: who, password: pw }),
    { headers: { 'Content-Type': 'application/json' }, tags: { kind: 'auth' } });
  check(reg, { 'register 200': (r) => r.status === 200 });

  const login = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email: who, password: pw }),
    { headers: { 'Content-Type': 'application/json' }, tags: { kind: 'auth' } });
  check(login, { 'login 200': (r) => r.status === 200 });

  // The login body IS the token - the browser gets a cookie, API clients get
  // the raw JWT. There is no JSON envelope to unwrap.
  const token = (login.body || '').trim();
  if (token.startsWith('eyJ')) {
    const me = http.get(`${BASE}/api/auth/me`,
      { headers: { Authorization: `Bearer ${token}` }, tags: { kind: 'auth' } });
    check(me, { 'me 200': (r) => r.status === 200 });
  }

  sleep(3 + Math.random() * 3);
}
