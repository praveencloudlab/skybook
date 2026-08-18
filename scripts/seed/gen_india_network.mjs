/**
 * India domestic network generator - THE one table of every Indian city with
 * scheduled commercial service, from which everything else derives:
 *
 *   node scripts/seed/gen_india_network.mjs
 *
 * emits (into scripts/seed/):
 *   12_india_flights.sql    additive year of dated departures, destination-local
 *   13_india_fleet.sql      the India fleet with HONEST cabins (ATR/A320 all-
 *                           economy; AI A320neo with a real J cabin)
 *   14_india_inventory.sql  per-flight hull assignment (route class decides)
 *   india_airports.json     the table itself, for patching the code directories
 *                           (AirportTimeZones, AirportCityLookup, frontend AIRPORTS)
 *
 * Design mirrors 06_full_mesh.sql: NOT EXISTS-guarded inserts keyed on
 * (flight_number, day), CURRENT_DATE-relative, arrivals authored on the
 * destination clock (both ends Asia/Kolkata here, so block time adds cleanly).
 *
 * Realism rules:
 *  - Carriers: 6E (IndiGo), AI (Air India), IX (AI Express), QP (Akasa), SG (SpiceJet).
 *  - Metro trunks (DEL/BOM/BLR/HYD/MAA/CCU pairs): 3 daily - one AI rotation on
 *    a two-cabin A320neo (Business sold ONLY here), two 6E all-economy.
 *  - Every other city connects to its nearest metros - 2 daily to the closest,
 *    1 to the second (majors also get a single to the third).
 *  - Hops under 420 km touching a regional field fly the ATR 72 (70Y) -
 *    the HYD-VTZ class of route that must never sell Business again.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const DIR = path.dirname(fileURLToPath(import.meta.url));

// ---- THE TABLE: code, city, name, lat, lon, tier (M metro / J major / R regional)
const AIRPORTS = [
  ['DEL', 'Delhi', 'Indira Gandhi Intl', 28.556, 77.100, 'M'],
  ['BOM', 'Mumbai', 'Chhatrapati Shivaji Maharaj Intl', 19.089, 72.868, 'M'],
  ['BLR', 'Bengaluru', 'Kempegowda Intl', 13.199, 77.706, 'M'],
  ['HYD', 'Hyderabad', 'Rajiv Gandhi Intl', 17.240, 78.429, 'M'],
  ['MAA', 'Chennai', 'Chennai Intl', 12.990, 80.169, 'M'],
  ['CCU', 'Kolkata', 'Netaji Subhas Chandra Bose Intl', 22.655, 88.447, 'M'],

  ['AMD', 'Ahmedabad', 'Sardar Vallabhbhai Patel Intl', 23.077, 72.635, 'J'],
  ['PNQ', 'Pune', 'Pune Airport', 18.582, 73.920, 'J'],
  ['GOI', 'Goa Dabolim', 'Dabolim Airport', 15.381, 73.831, 'J'],
  ['GOX', 'Goa Mopa', 'Manohar Intl', 15.744, 73.861, 'J'],
  ['COK', 'Kochi', 'Cochin Intl', 10.152, 76.393, 'J'],
  ['TRV', 'Thiruvananthapuram', 'Trivandrum Intl', 8.482, 76.920, 'J'],
  ['NAG', 'Nagpur', 'Dr. Babasaheb Ambedkar Intl', 21.092, 79.047, 'J'],
  ['JAI', 'Jaipur', 'Jaipur Intl', 26.824, 75.812, 'J'],
  ['LKO', 'Lucknow', 'Chaudhary Charan Singh Intl', 26.761, 80.889, 'J'],
  ['IXC', 'Chandigarh', 'Shaheed Bhagat Singh Intl', 30.673, 76.788, 'J'],
  ['ATQ', 'Amritsar', 'Sri Guru Ram Dass Jee Intl', 31.710, 74.797, 'J'],
  ['GAU', 'Guwahati', 'Lokpriya Gopinath Bordoloi Intl', 26.106, 91.586, 'J'],
  ['BBI', 'Bhubaneswar', 'Biju Patnaik Intl', 20.244, 85.818, 'J'],
  ['PAT', 'Patna', 'Jay Prakash Narayan Intl', 25.591, 85.088, 'J'],
  ['IDR', 'Indore', 'Devi Ahilya Bai Holkar', 22.722, 75.801, 'J'],
  ['BHO', 'Bhopal', 'Raja Bhoj', 23.287, 77.337, 'J'],
  ['RPR', 'Raipur', 'Swami Vivekananda', 21.180, 81.739, 'J'],
  ['VNS', 'Varanasi', 'Lal Bahadur Shastri Intl', 25.452, 82.859, 'J'],
  ['SXR', 'Srinagar', 'Sheikh ul-Alam Intl', 33.987, 74.774, 'J'],
  ['IXB', 'Siliguri', 'Bagdogra Airport', 26.681, 88.329, 'J'],
  ['CJB', 'Coimbatore', 'Coimbatore Intl', 11.030, 77.043, 'J'],
  ['CCJ', 'Kozhikode', 'Calicut Intl', 11.137, 75.955, 'J'],
  ['IXE', 'Mangaluru', 'Mangaluru Intl', 13.171, 74.890, 'J'],
  // Commercial ops moved from the INS Dega civil enclave to the new
  // Bhogapuram airport on 17 Aug 2026 - the VTZ code transferred with them
  // (user-confirmed). Coordinates are Bhogapuram's.
  ['VTZ', 'Visakhapatnam', 'Alluri Sitarama Raju Intl (Bhogapuram)', 18.033, 83.489, 'J'],
  ['IXZ', 'Port Blair', 'Veer Savarkar Intl', 11.641, 92.730, 'J'],
  ['IXR', 'Ranchi', 'Birsa Munda', 23.314, 85.322, 'J'],
  ['DED', 'Dehradun', 'Jolly Grant', 30.190, 78.180, 'J'],
  ['UDR', 'Udaipur', 'Maharana Pratap', 24.618, 73.896, 'J'],
  ['JDH', 'Jodhpur', 'Jodhpur Airport', 26.251, 73.049, 'J'],
  ['STV', 'Surat', 'Surat Intl', 21.114, 72.742, 'J'],

  ['CNN', 'Kannur', 'Kannur Intl', 11.918, 75.547, 'R'],
  ['IXM', 'Madurai', 'Madurai Airport', 9.834, 78.093, 'R'],
  ['TRZ', 'Tiruchirappalli', 'Tiruchirappalli Intl', 10.765, 78.710, 'R'],
  ['TIR', 'Tirupati', 'Tirupati Airport', 13.632, 79.543, 'R'],
  ['VGA', 'Vijayawada', 'Vijayawada Intl', 16.530, 80.797, 'R'],
  ['RJA', 'Rajahmundry', 'Rajahmundry Airport', 17.110, 81.818, 'R'],
  ['IXU', 'Aurangabad', 'Aurangabad Airport', 19.863, 75.398, 'R'],
  ['JLR', 'Jabalpur', 'Jabalpur Airport', 23.178, 80.052, 'R'],
  ['JRG', 'Jharsuguda', 'Veer Surendra Sai', 21.913, 84.050, 'R'],
  ['GAY', 'Gaya', 'Gaya Intl', 24.744, 84.951, 'R'],
  ['IXD', 'Prayagraj', 'Prayagraj Airport', 25.440, 81.734, 'R'],
  ['GWL', 'Gwalior', 'Rajmata Vijaya Raje Scindia', 26.293, 78.228, 'R'],
  ['JGA', 'Jamnagar', 'Jamnagar Airport', 22.465, 70.011, 'R'],
  // Commercial service moved wholesale to the greenfield Hirasar airport
  // (user-confirmed); the old city airport RAJ no longer takes scheduled
  // flights and must not be offered.
  ['HSR', 'Rajkot', 'Rajkot Intl (Hirasar)', 22.719, 70.953, 'R'],
  ['BHJ', 'Bhuj', 'Bhuj Airport', 23.288, 69.670, 'R'],
  ['BDQ', 'Vadodara', 'Vadodara Airport', 22.336, 73.226, 'R'],
  ['KNU', 'Kanpur', 'Kanpur Airport', 26.404, 80.410, 'R'],
  ['GOP', 'Gorakhpur', 'Gorakhpur Airport', 26.740, 83.450, 'R'],
  ['DBR', 'Darbhanga', 'Darbhanga Airport', 26.193, 85.917, 'R'],
  ['IXJ', 'Jammu', 'Jammu Airport', 32.689, 74.838, 'R'],
  ['IXL', 'Leh', 'Kushok Bakula Rimpochee', 34.136, 77.546, 'R'],
  ['DHM', 'Dharamshala', 'Kangra Airport', 32.165, 76.263, 'R'],
  ['KUU', 'Kullu', 'Bhuntar Airport', 31.877, 77.154, 'R'],
  ['IMF', 'Imphal', 'Bir Tikendrajit Intl', 24.760, 93.897, 'R'],
  ['DIB', 'Dibrugarh', 'Dibrugarh Airport', 27.484, 95.017, 'R'],
  ['JRH', 'Jorhat', 'Jorhat Airport', 26.732, 94.176, 'R'],
  ['SHL', 'Shillong', 'Shillong Airport', 25.703, 91.979, 'R'],
  ['AJL', 'Aizawl', 'Lengpui Airport', 23.840, 92.620, 'R'],
  ['DMU', 'Dimapur', 'Dimapur Airport', 25.884, 93.771, 'R'],
  ['IXA', 'Agartala', 'Maharaja Bir Bikram', 23.887, 91.240, 'R'],
  ['IXS', 'Silchar', 'Silchar Airport', 24.913, 92.979, 'R'],
  ['HBX', 'Hubballi', 'Hubballi Airport', 15.362, 75.085, 'R'],
  ['MYQ', 'Mysuru', 'Mysuru Airport', 12.230, 76.656, 'R'],
  ['PGH', 'Pantnagar', 'Pantnagar Airport', 29.033, 79.474, 'R'],
  ['TEZ', 'Tezpur', 'Tezpur Airport', 26.709, 92.785, 'R'],
];

const byCode = Object.fromEntries(AIRPORTS.map((a) => [a[0], a]));
const METROS = AIRPORTS.filter((a) => a[5] === 'M').map((a) => a[0]);

const rad = (d) => (d * Math.PI) / 180;
const km = (a, b) => {
  const [, , , la1, lo1] = byCode[a];
  const [, , , la2, lo2] = byCode[b];
  const dLat = rad(la2 - la1), dLon = rad(lo2 - lo1);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(la1)) * Math.cos(rad(la2)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(h));
};
// Block minutes: cruise ~735 km/h + 38 min taxi/climb/descent, 5-min rounded.
const blockMins = (a, b) => Math.max(45, Math.round((km(a, b) / 12.25 + 38) / 5) * 5);

// Deterministic tiny hash for jitter/carrier spread - stable across runs.
const hash = (s) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);

// ---- route construction ----------------------------------------------------
// Each entry: origin, dest, carrier, fnum, depMinutes(local), block, hull
const routes = [];
const counters = { '6E': 2001, AI: 501, IX: 1201, QP: 1301, SG: 701 };
const nextNum = (c) => `${c}${counters[c]++}`;

const hullFor = (carrier, o, d) => {
  const dist = km(o, d);
  const regionalHop = (byCode[o][5] === 'R' || byCode[d][5] === 'R') && dist < 420;
  if (regionalHop) return 'ATR72';
  if (carrier === 'AI') return 'AI320'; // two-cabin: the only Business domestically
  if (carrier === '6E') return dist > 1300 ? 'A321N' : 'A320N';
  return 'B738'; // IX / QP / SG
};

const addRoute = (o, d, carrier, depMin) => {
  const jitter = hash(o + d + carrier + depMin) % 25;
  routes.push({
    o, d, carrier, fnum: nextNum(carrier),
    dep: depMin + jitter, block: blockMins(o, d), hull: hullFor(carrier, o, d),
  });
};

const CARRIER_POOL = ['6E', '6E', '6E', 'IX', 'QP', 'SG'];
const pick = (o, d, salt) => CARRIER_POOL[hash(o + d + salt) % CARRIER_POOL.length];

// Metro trunks: AI two-cabin rotation + two 6E waves, both directions.
for (const o of METROS) {
  for (const d of METROS) {
    if (o === d) continue;
    addRoute(o, d, 'AI', 8 * 60);
    addRoute(o, d, '6E', 6 * 60 + 30);
    addRoute(o, d, '6E', 18 * 60 + 20);
  }
}

// Everyone else: nearest metros, frequency by tier.
for (const [code, , , , , tier] of AIRPORTS) {
  if (tier === 'M') continue;
  const nearest = [...METROS].sort((a, b) => km(code, a) - km(code, b));
  const spokes = tier === 'J'
    ? [[nearest[0], 2], [nearest[1], 1], [nearest[2], 1]]
    : [[nearest[0], 2], [nearest[1], 1]];
  for (const [metro, daily] of spokes) {
    const slots = daily === 2 ? [7 * 60 + 15, 16 * 60 + 45] : [11 * 60 + 25];
    for (const s of slots) {
      const carrier = pick(code, metro, String(s));
      addRoute(metro, code, carrier, s);                    // out from the hub
      addRoute(code, metro, carrier, s + blockMins(code, metro) + 40); // turnaround back
    }
  }
}

console.log(`airports ${AIRPORTS.length}, routes ${routes.length}, flights/day ${routes.length}`);

// ---- 12_india_flights.sql --------------------------------------------------
const t = (mins) => `${String(Math.floor((mins % 1440) / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
const routeValues = routes
  .map((r) => `  ('${r.o}','${r.d}','${r.carrier.slice(0, 2)}','${r.fnum}',TIME '${t(r.dep)}',${r.block})`)
  .join(',\n');

fs.writeFileSync(path.join(DIR, '12_india_flights.sql'), `BEGIN;

-- India domestic network (generated by gen_india_network.mjs - edit THAT, not
-- this): ${AIRPORTS.length} airports, ${routes.length} daily departures, a year from
-- CURRENT_DATE. ADDITIVE + idempotent, keyed on (flight_number, day) like
-- 06_full_mesh.sql. Both endpoints are Asia/Kolkata, so the destination-local
-- arrival is departure + block time with no zone conversion.
-- Run against skybook_flight.

CREATE TEMP TABLE india_routes (
  origin varchar(3), destination varchar(3), airline varchar(2),
  flight_number varchar(10), dep_local time, block_mins int
);
INSERT INTO india_routes VALUES
${routeValues};

INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed-india', NULL, 0,
  r.airline,
  (d::date + r.dep_local) + make_interval(mins => r.block_mins),
  (d::date + r.dep_local),
  r.destination, r.flight_number, r.origin, 'SCHEDULED', NULL
FROM india_routes r
CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + 365, INTERVAL '1 day') AS d
WHERE NOT EXISTS (
  SELECT 1 FROM flights f
  WHERE f.flight_number = r.flight_number AND f.departure_time::date = d::date
);

\\echo india flights now in table:
SELECT count(*) AS india_flights, min(departure_time)::date AS first_day, max(departure_time)::date AS last_day
FROM flights WHERE created_by = 'data-seed-india';

DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM flights WHERE created_by = 'data-seed-india';
  IF n = 0 THEN
    RAISE EXCEPTION 'India seed produced 0 flights.';
  END IF;
END $$;

COMMIT;
`);

// ---- 13_india_fleet.sql ----------------------------------------------------
const seatBlock = (cls, rows, letters, exits = []) => `
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-india', 0, v_id, r || l.letter, r, '${cls}', l.pos, r IN (${exits.length ? exits.join(', ') : '-1'}), 'ACTIVE'
    FROM generate_series(${rows[0]}, ${rows[1]}) r,
         (VALUES ${letters}) AS l(letter, pos);`;

const Y33 = `('A','WINDOW'),('B','MIDDLE'),('C','AISLE'),('D','AISLE'),('E','MIDDLE'),('F','WINDOW')`;
const J22 = `('A','WINDOW'),('C','AISLE'),('D','AISLE'),('F','WINDOW')`;
const ATR22 = `('A','WINDOW'),('C','AISLE'),('D','AISLE'),('F','WINDOW')`;

const hull = (reg, manufacturer, model, total, blocks) => `
  IF NOT EXISTS (SELECT 1 FROM aircraft WHERE registration_number = '${reg}') THEN
    INSERT INTO aircraft (created_at, updated_at, created_by, version, registration_number, manufacturer, model, status, total_seats)
    VALUES (now(), now(), 'data-seed-india', 0, '${reg}', '${manufacturer}', '${model}', 'ACTIVE', ${total})
    RETURNING id INTO v_id;
${blocks}
  END IF;`;

fs.writeFileSync(path.join(DIR, '13_india_fleet.sql'), `-- India fleet (generated by gen_india_network.mjs): honest cabins per hull.
-- ATR 72 and every LCC narrow-body are ALL-ECONOMY - the whole point of the
-- exercise; only the AI A320neo carries a small Business cabin, sold on metro
-- trunks. Idempotent by registration. Run against skybook_inventory.

DO $$
DECLARE
  v_id bigint;
BEGIN
${hull('VT-IGA', 'Airbus', 'A320neo (IndiGo)', 180, seatBlock('ECONOMY', [1, 30], Y33, [12, 13]))}
${hull('VT-IGB', 'Airbus', 'A321neo (IndiGo)', 222, seatBlock('ECONOMY', [1, 37], Y33, [11, 24]))}
${hull('VT-EXA', 'Airbus', 'A320neo (Air India)', 164, seatBlock('BUSINESS', [1, 2], J22) + seatBlock('ECONOMY', [3, 28], Y33, [12, 13]))}
${hull('VT-AXB', 'Boeing', '737-8 (AI Express)', 186, seatBlock('ECONOMY', [1, 31], Y33, [14, 15]))}
${hull('VT-QPB', 'Boeing', '737-8 (Akasa Air)', 186, seatBlock('ECONOMY', [1, 31], Y33, [14, 15]))}
${hull('VT-SGB', 'Boeing', '737-800 (SpiceJet)', 186, seatBlock('ECONOMY', [1, 31], Y33, [14, 15]))}
${hull('VT-ATA', 'ATR', '72-600', 70, seatBlock('ECONOMY', [1, 17], ATR22, [1, 12]) + `
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-india', 0, v_id, '18' || l.letter, 18, 'ECONOMY', l.pos, false, 'ACTIVE'
    FROM (VALUES ('A','WINDOW'),('C','AISLE')) AS l(letter, pos);`)}
END $$;
`);

// ---- 14_india_inventory.sql ------------------------------------------------
const hullMap = routes.map((r) => `  ('${r.fnum}','${r.hull}')`).join(',\n');
fs.writeFileSync(path.join(DIR, '14_india_inventory.sql'), `-- India per-flight hulls (generated by gen_india_network.mjs). Expects
-- tmp_india_flights(flight_id, flight_number) staged by seed_india.sh.
-- Additive: only flights without inventory get a record, so re-runs and
-- flights with sold seats are untouched. Run against skybook_inventory.

CREATE TEMP TABLE india_hulls (flight_number varchar(10), hull varchar(6));
INSERT INTO india_hulls VALUES
${hullMap};

WITH regs AS (
  SELECT h.flight_number,
         CASE h.hull
           WHEN 'ATR72' THEN (SELECT id FROM aircraft WHERE registration_number = 'VT-ATA')
           WHEN 'AI320' THEN (SELECT id FROM aircraft WHERE registration_number = 'VT-EXA')
           WHEN 'A320N' THEN (SELECT id FROM aircraft WHERE registration_number = 'VT-IGA')
           WHEN 'A321N' THEN (SELECT id FROM aircraft WHERE registration_number = 'VT-IGB')
           ELSE (SELECT id FROM aircraft WHERE registration_number = CASE substring(h.flight_number, 1, 2)
                   WHEN 'IX' THEN 'VT-AXB' WHEN 'QP' THEN 'VT-QPB' ELSE 'VT-SGB' END)
         END AS aircraft_id
  FROM india_hulls h
)
INSERT INTO flight_inventory
  (created_at, updated_at, created_by, version, flight_id, aircraft_id, status,
   total_seats, available_seats, held_seats, reserved_seats, blocked_seats)
SELECT now(), now(), 'data-seed-india', 0, t.flight_id, r.aircraft_id, 'OPEN',
  a.total_seats, a.total_seats, 0, 0, 0
FROM tmp_india_flights t
JOIN regs r ON r.flight_number = t.flight_number
JOIN aircraft a ON a.id = r.aircraft_id
WHERE NOT EXISTS (SELECT 1 FROM flight_inventory fi WHERE fi.flight_id = t.flight_id);

SELECT a.registration_number, a.model, count(*) AS flights
FROM flight_inventory fi JOIN aircraft a ON a.id = fi.aircraft_id
WHERE fi.created_by = 'data-seed-india'
GROUP BY 1, 2 ORDER BY 3 DESC;
`);

// ---- india_airports.json for the code directories --------------------------
fs.writeFileSync(path.join(DIR, 'india_airports.json'), JSON.stringify(
  AIRPORTS.map(([code, city, name, lat, lon, tier]) => ({ code, city, name, lat, lon, tier })), null, 2,
));

console.log('wrote 12_india_flights.sql, 13_india_fleet.sql, 14_india_inventory.sql, india_airports.json');
