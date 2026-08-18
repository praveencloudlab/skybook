/**
 * UK domestic + Crown Dependency network generator - built from the CAA
 * June 2026 Table 12.2 route observations (real demand weights) and the
 * verified carrier/cabin reference. Edit THIS, then:
 *
 *   node scripts/seed/gen_uk_network.mjs
 *
 * emits (into scripts/seed/):
 *   15_uk_fleet.sql      UK fleet with honest cabins (BA two-class Airbus +
 *                        CityFlyer E190 are the ONLY domestic Business;
 *                        everyone else - easyJet/Loganair/Emerald/Ryanair UK/
 *                        Aurigny/Skybus - is all-economy)
 *   16_uk_flights.sql    additive year of dated departures, effective-window
 *                        aware (Loganair Heathrow-Dundee ENDS 23 Oct 2026;
 *                        Skybus Newquay-Scilly resumes 1 Sep 2026)
 *   17_uk_inventory.sql  per-flight hull assignment
 *   uk_airports.json     airport table for the code directories
 *
 * Verified corrections applied (web-checked 18 Aug 2026):
 *  - Gatwick-Glasgow is BA-coded but flown by an Emerald Airlines UK ATR 72
 *    wet-lease: NO Club Europe on that route.
 *  - Emerald Airlines UK is its own AOC (ICAO EAG), distinct from Irish EAI.
 *  - Eastern Airways (T3) ceased 27 Oct 2025 - deliberately absent.
 *
 * Frequency derives from CAA monthly pax (both directions combined):
 *   dailyPerDirection = clamp(round(pax / 30 / 2 / (seats * 0.75)), 1, 8)
 * Noise rule: strict-UK pairs under 1,500 pax/month are skipped (CAA
 * low-volume rows can be positioning) UNLESS they serve an island/PSO
 * community; Crown pairs under 1,000 skipped except lifeline Alderney.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const DIR = path.dirname(fileURLToPath(import.meta.url));

// ---- airports: code, city, name, lat, lon (all Europe/London) -------------
const AIRPORTS = [
  ['LHR', 'London Heathrow', 'London Heathrow', 51.470, -0.454],
  ['LGW', 'London Gatwick', 'London Gatwick', 51.148, -0.190],
  ['STN', 'London Stansted', 'London Stansted', 51.885, 0.235],
  ['LTN', 'London Luton', 'London Luton', 51.874, -0.368],
  ['LCY', 'London City', 'London City', 51.505, 0.055],
  ['SEN', 'London Southend', 'London Southend', 51.571, 0.696],
  ['MAN', 'Manchester', 'Manchester', 53.354, -2.275],
  ['LPL', 'Liverpool', 'Liverpool John Lennon', 53.334, -2.850],
  ['BHX', 'Birmingham', 'Birmingham', 52.454, -1.748],
  ['EMA', 'East Midlands', 'East Midlands', 52.831, -1.328],
  ['NWI', 'Norwich', 'Norwich', 52.676, 1.283],
  ['LBA', 'Leeds Bradford', 'Leeds Bradford', 53.866, -1.661],
  ['NCL', 'Newcastle', 'Newcastle Intl', 55.038, -1.692],
  ['MME', 'Teesside', 'Teesside Intl', 54.509, -1.429],
  ['BOH', 'Bournemouth', 'Bournemouth', 50.780, -1.843],
  ['SOU', 'Southampton', 'Southampton', 50.950, -1.357],
  ['EXT', 'Exeter', 'Exeter', 50.734, -3.414],
  ['BRS', 'Bristol', 'Bristol', 51.383, -2.719],
  ['CWL', 'Cardiff', 'Cardiff', 51.397, -3.343],
  ['NQY', 'Newquay', 'Cornwall Airport Newquay', 50.441, -5.006],
  ['LEQ', "Land's End", "Land's End", 50.103, -5.670],
  ['ISC', 'Isles of Scilly', "St Mary's", 49.913, -6.292],
  ['EDI', 'Edinburgh', 'Edinburgh', 55.950, -3.373],
  ['GLA', 'Glasgow', 'Glasgow', 55.870, -4.433],
  ['ABZ', 'Aberdeen', 'Aberdeen Intl', 57.202, -2.198],
  ['INV', 'Inverness', 'Inverness', 57.543, -4.048],
  ['DND', 'Dundee', 'Dundee', 56.453, -3.026],
  ['KOI', 'Kirkwall', 'Kirkwall', 58.958, -2.905],
  ['LSI', 'Shetland', 'Sumburgh', 59.879, -1.296],
  ['SYY', 'Stornoway', 'Stornoway', 58.216, -6.331],
  ['BEB', 'Benbecula', 'Benbecula', 57.481, -7.363],
  ['BRR', 'Barra', 'Barra', 57.023, -7.443],
  ['TRE', 'Tiree', 'Tiree', 56.499, -6.869],
  ['ILY', 'Islay', 'Islay', 55.682, -6.257],
  ['CAL', 'Campbeltown', 'Campbeltown', 55.437, -5.687],
  ['PPW', 'Papa Westray', 'Papa Westray', 59.352, -2.900],
  ['NRL', 'North Ronaldsay', 'North Ronaldsay', 59.368, -2.434],
  ['NDY', 'Sanday', 'Sanday', 59.250, -2.577],
  ['WRY', 'Westray', 'Westray', 59.350, -2.950],
  ['SOY', 'Stronsay', 'Stronsay', 59.155, -2.641],
  ['BFS', 'Belfast Intl', 'Belfast Intl', 54.658, -6.216],
  ['BHD', 'Belfast City', 'George Best Belfast City', 54.618, -5.872],
  ['LDY', 'Derry', 'City of Derry', 55.043, -7.161],
  ['JER', 'Jersey', 'Jersey', 49.208, -2.195],
  ['GCI', 'Guernsey', 'Guernsey', 49.435, -2.602],
  ['ACI', 'Alderney', 'Alderney', 49.706, -2.215],
  ['IOM', 'Isle of Man', 'Isle of Man (Ronaldsway)', 54.083, -4.624],
];

// ---- hulls: tag -> [registration, manufacturer, model, cabins] ------------
// cabins: list of [class, rows, layout] - layout '3-3' | '2-2' | '1-2'
const HULLS = {
  BAJ: ['G-SBBA', 'Airbus', 'A320 (BA Club Europe)', [['BUSINESS', 3, '2-2'], ['ECONOMY', 25, '3-3']]], // 12J+150Y
  BAC: ['G-SBBC', 'Embraer', 'E190 (BA CityFlyer)', [['BUSINESS', 3, '2-2'], ['ECONOMY', 22, '2-2']]],  // 12J+88Y
  U2A: ['G-EZSB', 'Airbus', 'A320 (easyJet)', [['ECONOMY', 30, '3-3']]],                                 // 180Y
  U2B: ['G-EZSA', 'Airbus', 'A319 (easyJet)', [['ECONOMY', 26, '3-3']]],                                 // 156Y
  EIA: ['G-EAGS', 'ATR', '72-600 (Emerald UK)', [['ECONOMY', 18, '2-2']]],                               // 72Y
  LMA: ['G-LMSA', 'ATR', '72-600 (Loganair)', [['ECONOMY', 18, '2-2']]],                                 // 72Y
  LM4: ['G-LMSB', 'ATR', '42-500 (Loganair)', [['ECONOMY', 12, '2-2']]],                                 // 48Y
  LME: ['G-LMSC', 'Embraer', 'ERJ145 (Loganair)', [['ECONOMY', 16, '1-2']]],                             // 48Y
  LMT: ['G-LMST', 'De Havilland', 'DHC-6 Twin Otter (Loganair)', [['ECONOMY', 5, '2-2']]],               // 20Y
  LMI: ['G-LMSI', 'Britten-Norman', 'BN-2 Islander (Loganair)', [['ECONOMY', 4, '1-1']]],                // 8Y
  RKA: ['G-RUKS', 'Boeing', '737-800 (Ryanair UK)', [['ECONOMY', 32, '3-3']]],                           // 192Y
  GRA: ['G-GRSA', 'ATR', '72-600 (Aurigny)', [['ECONOMY', 18, '2-2']]],                                  // 72Y
  GRD: ['G-GRSD', 'Dornier', '228 (Aurigny)', [['ECONOMY', 5, '2-2']]],                                  // 20Y
  YT8: ['G-SBYT', 'De Havilland', 'DHC-6 Twin Otter (Skybus)', [['ECONOMY', 5, '2-2']]],                 // 20Y
};
const seatsOf = (tag) => HULLS[tag][3].reduce((s, [, rows, lay]) => s + rows * (lay === '3-3' ? 6 : lay === '2-2' ? 4 : lay === '1-2' ? 3 : 2), 0);

// ---- routes: [a, b, monthlyPax, airline, hull, {start,end}?] --------------
// pax = CAA Jun 2026 Table 12.2 (both directions). One row = both directions.
const R = [];
const r = (a, b, pax, al, hull, win) => R.push({ a, b, pax, al, hull, win });

// BA mainline two-class from Heathrow (Club Europe = the only UK domestic J)
r('LHR', 'EDI', 90214, 'BA', 'BAJ'); r('LHR', 'GLA', 81271, 'BA', 'BAJ');
r('LHR', 'BHD', 49438, 'BA', 'BAJ'); r('LHR', 'ABZ', 43613, 'BA', 'BAJ');
r('LHR', 'MAN', 40675, 'BA', 'BAJ'); r('LHR', 'NCL', 33063, 'BA', 'BAJ');
r('LHR', 'INV', 26056, 'BA', 'BAJ'); r('LHR', 'JER', 22894, 'BA', 'BAJ');
r('LHR', 'GCI', 6695, 'BA', 'BAJ'); // daily since Apr 2026 (verified)
// BA CityFlyer E190 two-class from London City
r('LCY', 'EDI', 25004, 'BA', 'BAC'); r('LCY', 'GLA', 19399, 'BA', 'BAC');
r('LCY', 'BHD', 9836, 'BA', 'BAC'); r('LCY', 'GCI', 2132, 'BA', 'BAC');
// BA-coded, Emerald ATR wet-lease: Economy ONLY (verified correction)
r('LGW', 'GLA', 43236, 'BA', 'EIA');
// easyJet
r('LGW', 'EDI', 34390, 'U2', 'U2A'); r('LGW', 'BFS', 31653, 'U2', 'U2A');
r('LGW', 'BHD', 24759, 'U2', 'U2A'); r('LGW', 'INV', 20562, 'U2', 'U2A');
r('LGW', 'ABZ', 17326, 'U2', 'U2A'); r('LGW', 'JER', 38218, 'U2', 'U2A');
r('LGW', 'IOM', 16443, 'U2', 'U2B');
r('LTN', 'EDI', 26885, 'U2', 'U2A'); r('LTN', 'BFS', 26175, 'U2', 'U2A');
r('LTN', 'GLA', 24957, 'U2', 'U2A'); r('LTN', 'INV', 13782, 'U2', 'U2B');
r('LTN', 'BHD', 10330, 'U2', 'U2B'); r('LTN', 'ABZ', 6095, 'U2', 'U2B');
r('LTN', 'JER', 10938, 'U2', 'U2B'); r('LTN', 'IOM', 1211, 'U2', 'U2B');
r('BFS', 'MAN', 44477, 'U2', 'U2A'); r('BFS', 'EDI', 41136, 'U2', 'U2A');
r('BFS', 'BHX', 27670, 'U2', 'U2A'); r('BFS', 'LPL', 27431, 'U2', 'U2A');
r('BFS', 'GLA', 24241, 'U2', 'U2A'); r('BFS', 'BRS', 23527, 'U2', 'U2A');
r('BFS', 'NCL', 20240, 'U2', 'U2A'); r('BFS', 'EMA', 3747, 'U2', 'U2B');
r('BFS', 'LBA', 3599, 'U2', 'U2B'); r('BFS', 'SOU', 4603, 'U2', 'U2B');
r('BFS', 'JER', 2492, 'U2', 'U2B');
r('BRS', 'EDI', 32392, 'U2', 'U2A'); r('BRS', 'GLA', 24366, 'U2', 'U2A');
r('BRS', 'NCL', 11914, 'U2', 'U2B'); r('BRS', 'INV', 9032, 'U2', 'U2B');
r('BRS', 'IOM', 2629, 'U2', 'U2B'); r('BRS', 'JER', 3320, 'U2', 'U2B');
r('BHX', 'EDI', 21061, 'U2', 'U2A'); r('BHX', 'GLA', 12392, 'U2', 'U2B');
r('BHX', 'INV', 2596, 'U2', 'U2B'); r('BHX', 'JER', 2275, 'U2', 'U2B');
r('BOH', 'EDI', 5115, 'U2', 'U2B');
r('IOM', 'LPL', 15080, 'U2', 'U2B'); r('IOM', 'MAN', 12541, 'U2', 'U2B');
r('JER', 'LPL', 8356, 'U2', 'U2B'); r('JER', 'MAN', 7697, 'U2', 'U2B');
r('JER', 'NCL', 2506, 'U2', 'U2B'); r('JER', 'EXT', 2389, 'U2', 'U2B');
r('JER', 'EDI', 2383, 'U2', 'U2B'); r('JER', 'GLA', 4445, 'U2', 'U2B');
r('JER', 'EMA', 3890, 'U2', 'U2B'); r('JER', 'SEN', 3057, 'U2', 'U2B');
r('MAN', 'NQY', 6705, 'U2', 'U2B');
// Ryanair UK from Stansted
r('STN', 'EDI', 57912, 'RK', 'RKA'); r('STN', 'BFS', 51996, 'RK', 'RKA');
r('STN', 'GLA', 36746, 'RK', 'RKA'); r('STN', 'NQY', 5708, 'RK', 'RKA');
// Aer Lingus Regional (Emerald UK) from Belfast City
r('BHD', 'MAN', 20846, 'EI', 'EIA'); r('BHD', 'BHX', 18810, 'EI', 'EIA');
r('BHD', 'EDI', 17561, 'EI', 'EIA'); r('BHD', 'LBA', 11774, 'EI', 'EIA');
r('BHD', 'SOU', 10125, 'EI', 'EIA'); r('BHD', 'GLA', 9477, 'EI', 'EIA');
r('BHD', 'LPL', 6100, 'EI', 'EIA'); r('BHD', 'CWL', 3711, 'EI', 'EIA');
r('BHD', 'EMA', 2837, 'EI', 'EIA'); r('BHD', 'BRS', 2540, 'EI', 'EIA');
// Loganair - jets/turboprops to the highlands, islands and regions
r('ABZ', 'LSI', 12895, 'LM', 'LMA'); r('ABZ', 'KOI', 3936, 'LM', 'LMA');
r('ABZ', 'MAN', 5048, 'LM', 'LME'); r('ABZ', 'BHX', 4140, 'LM', 'LME');
r('ABZ', 'NWI', 2349, 'LM', 'LME'); r('ABZ', 'NQY', 1511, 'LM', 'LME');
r('EDI', 'LSI', 4294, 'LM', 'LMA'); r('EDI', 'KOI', 3993, 'LM', 'LMA');
r('EDI', 'SYY', 2030, 'LM', 'LM4'); r('EDI', 'SOU', 8060, 'LM', 'LME');
r('EDI', 'EXT', 2226, 'LM', 'LME'); r('EDI', 'NQY', 4510, 'LM', 'LME');
r('EDI', 'IOM', 837, 'LM', 'LM4');
r('GLA', 'SYY', 6142, 'LM', 'LMA'); r('GLA', 'ILY', 2862, 'LM', 'LM4');
r('GLA', 'KOI', 2397, 'LM', 'LMA'); r('GLA', 'LSI', 2192, 'LM', 'LMA');
r('GLA', 'BEB', 2187, 'LM', 'LM4'); r('GLA', 'BRR', 1205, 'LM', 'LMT');
r('GLA', 'TRE', 848, 'LM', 'LM4'); r('GLA', 'CAL', 710, 'LM', 'LMT');
r('GLA', 'SOU', 7416, 'LM', 'LME');
r('INV', 'MAN', 2104, 'LM', 'LME'); r('INV', 'SYY', 1589, 'LM', 'LM4');
r('INV', 'KOI', 1567, 'LM', 'LM4'); r('KOI', 'LSI', 862, 'LM', 'LM4');
r('NCL', 'SOU', 3105, 'LM', 'LME'); r('MAN', 'SOU', 2832, 'LM', 'LME');
r('LHR', 'LDY', 6791, 'LM', 'LME'); // PSO
r('LDY', 'MAN', 5421, 'LM', 'LME'); r('LDY', 'LPL', 3943, 'LM', 'LME');
r('LDY', 'EDI', 1792, 'LM', 'LM4'); r('LDY', 'GLA', 1107, 'LM', 'LM4');
r('BHX', 'LDY', 2349, 'LM', 'LME');
r('LCY', 'IOM', 1598, 'LM', 'LM4'); r('BHD', 'IOM', 866, 'LM', 'LM4');
// Loganair Heathrow-Dundee: ENDS 23 Oct 2026 (Dundee base closure, 14 Aug 2026)
r('LHR', 'DND', 3232, 'LM', 'LME', { end: '2026-10-23' });
// Orkney inter-island PSO - the 8-seat Islander
r('KOI', 'NRL', 559, 'LM', 'LMI'); r('KOI', 'PPW', 435, 'LM', 'LMI');
r('KOI', 'NDY', 248, 'LM', 'LMI'); r('KOI', 'WRY', 219, 'LM', 'LMI');
r('KOI', 'SOY', 206, 'LM', 'LMI');
// Skybus - Scilly lifeline. Newquay link suspended until end of Aug 2026.
r('LEQ', 'ISC', 4800, '8Y', 'YT8');
r('ISC', 'NQY', 588, '8Y', 'YT8', { start: '2026-09-01' });
r('EXT', 'ISC', 250, '8Y', 'YT8');
// Aurigny - Guernsey / Alderney lifeline
r('LGW', 'GCI', 22629, 'GR', 'GRA'); r('GCI', 'SOU', 8171, 'GR', 'GRA');
r('GCI', 'JER', 5994, 'GR', 'GRA'); r('GCI', 'MAN', 5629, 'GR', 'GRA');
r('BHX', 'GCI', 3298, 'GR', 'GRA');
r('ACI', 'GCI', 2412, 'GR', 'GRD'); r('ACI', 'SOU', 1549, 'GR', 'GRD');

// ---- schedule construction ------------------------------------------------
const byCode = Object.fromEntries(AIRPORTS.map((a) => [a[0], a]));
const rad = (d) => (d * Math.PI) / 180;
const km = (a, b) => {
  const [, , , la1, lo1] = byCode[a]; const [, , , la2, lo2] = byCode[b];
  const dLat = rad(la2 - la1), dLon = rad(lo2 - lo1);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(la1)) * Math.cos(rad(la2)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(h));
};
// Block time by hull class: jets cruise fast, turboprops slower, Islanders low+slow.
const SPEED = { BAJ: [11.7, 30], BAC: [11.2, 28], U2A: [11.7, 30], U2B: [11.7, 30], RKA: [11.7, 30],
  EIA: [7.5, 22], LMA: [7.5, 22], LM4: [7.2, 20], GRA: [7.5, 22], LME: [10, 25],
  LMT: [4.2, 12], LMI: [3.5, 8], GRD: [4.5, 12], YT8: [4.2, 12] };
const blockMins = (a, b, hull) => {
  const [v, ovh] = SPEED[hull];
  return Math.max(20, Math.round((km(a, b) / v + ovh) / 5) * 5);
};
const hash = (s) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x));

const counters = { BA: 1420, U2: 802, LM: 302, EI: 3602, RK: 4102, GR: 602, '8Y': 302 };
const BA_LCY = { n: 8752 }; // CityFlyer block, BA87xx
const nextNum = (al, hull) => {
  if (al === 'BA' && hull === 'BAC') return `BA${BA_LCY.n++}`;
  return `${al}${counters[al]++}`;
};

const flights = []; // {a,b,al,fnum,dep,block,hull,win}
for (const route of R) {
  const daily = clamp(Math.round(route.pax / 30 / 2 / (seatsOf(route.hull) * 0.75)), 1, 8);
  for (let dir = 0; dir < 2; dir++) {
    const [o, d] = dir === 0 ? [route.a, route.b] : [route.b, route.a];
    for (let i = 0; i < daily; i++) {
      const span = 14.5 * 60; // 06:30 .. 21:00
      const base = 6.5 * 60 + Math.round((span / daily) * (i + (dir ? 0.5 : 0)));
      const dep = base + (hash(o + d + i) % 20);
      flights.push({ o, d, al: route.al, fnum: nextNum(route.al, route.hull),
        dep, block: blockMins(o, d, route.hull), hull: route.hull, win: route.win });
    }
  }
}
console.log(`airports ${AIRPORTS.length}, routes ${R.length}, daily departures ${flights.length}`);

// ---- 16_uk_flights.sql ----------------------------------------------------
const t = (m) => `${String(Math.floor((m % 1440) / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
const vals = flights.map((f) => {
  const s = f.win?.start ? `DATE '${f.win.start}'` : 'NULL';
  const e = f.win?.end ? `DATE '${f.win.end}'` : 'NULL';
  return `  ('${f.o}','${f.d}','${f.al}','${f.fnum}',TIME '${t(f.dep)}',${f.block},${s},${e})`;
}).join(',\n');

fs.writeFileSync(path.join(DIR, '16_uk_flights.sql'), `BEGIN;

-- UK domestic + Crown Dependency network (generated by gen_uk_network.mjs -
-- edit THAT, not this): ${AIRPORTS.length} airports, ${flights.length} daily departures
-- derived from CAA June 2026 demand, a year from CURRENT_DATE. ADDITIVE +
-- idempotent, keyed on (flight_number, day). Route effective windows are
-- honoured (Heathrow-Dundee sunset, Newquay-Scilly resumption). All times
-- Europe/London both ends. Run against skybook_flight.

CREATE TEMP TABLE uk_routes (
  origin varchar(3), destination varchar(3), airline varchar(2),
  flight_number varchar(10), dep_local time, block_mins int,
  eff_start date, eff_end date
);
INSERT INTO uk_routes VALUES
${vals};

INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed-uk', NULL, 0,
  r.airline,
  (d::date + r.dep_local) + make_interval(mins => r.block_mins),
  (d::date + r.dep_local),
  r.destination, r.flight_number, r.origin, 'SCHEDULED', NULL
FROM uk_routes r
CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + 365, INTERVAL '1 day') AS d
WHERE (r.eff_start IS NULL OR d::date >= r.eff_start)
  AND (r.eff_end IS NULL OR d::date <= r.eff_end)
  AND NOT EXISTS (
    SELECT 1 FROM flights f
    WHERE f.flight_number = r.flight_number AND f.departure_time::date = d::date
  );

\\echo uk flights now in table:
SELECT count(*) AS uk_flights, min(departure_time)::date AS first_day, max(departure_time)::date AS last_day
FROM flights WHERE created_by = 'data-seed-uk';

DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM flights WHERE created_by = 'data-seed-uk';
  IF n = 0 THEN
    RAISE EXCEPTION 'UK seed produced 0 flights.';
  END IF;
END $$;

COMMIT;
`);

// ---- 15_uk_fleet.sql ------------------------------------------------------
const LAYOUTS = {
  '3-3': `('A','WINDOW'),('B','MIDDLE'),('C','AISLE'),('D','AISLE'),('E','MIDDLE'),('F','WINDOW')`,
  '2-2': `('A','WINDOW'),('C','AISLE'),('D','AISLE'),('F','WINDOW')`,
  '1-2': `('A','WINDOW'),('C','AISLE'),('D','WINDOW')`,
  '1-1': `('A','WINDOW'),('C','WINDOW')`,
};
let fleetSql = `-- UK fleet (generated by gen_uk_network.mjs): honest cabins per hull.
-- BA's two-class Airbus and CityFlyer E190 are the ONLY UK domestic
-- Business (Club Europe); every LCC, regional, island and Crown carrier
-- hull is all-economy. Idempotent by registration. Run against
-- skybook_inventory.

DO $$
DECLARE
  v_id bigint;
BEGIN`;
for (const [tag, [reg, man, model, cabins]] of Object.entries(HULLS)) {
  const total = seatsOf(tag);
  let rowStart = 1, blocks = '';
  for (const [cls, rows, lay] of cabins) {
    blocks += `
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-uk', 0, v_id, r || l.letter, r, '${cls}', l.pos, false, 'ACTIVE'
    FROM generate_series(${rowStart}, ${rowStart + rows - 1}) r,
         (VALUES ${LAYOUTS[lay]}) AS l(letter, pos);`;
    rowStart += rows;
  }
  fleetSql += `
  IF NOT EXISTS (SELECT 1 FROM aircraft WHERE registration_number = '${reg}') THEN
    INSERT INTO aircraft (created_at, updated_at, created_by, version, registration_number, manufacturer, model, status, total_seats)
    VALUES (now(), now(), 'data-seed-uk', 0, '${reg}', '${man}', '${model}', 'ACTIVE', ${total})
    RETURNING id INTO v_id;
${blocks}
  END IF;`;
}
fleetSql += `
END $$;
`;
fs.writeFileSync(path.join(DIR, '15_uk_fleet.sql'), fleetSql);

// ---- 17_uk_inventory.sql --------------------------------------------------
const hullRows = flights.map((f) => `  ('${f.fnum}','${f.hull}')`).join(',\n');
const regCase = Object.entries(HULLS)
  .map(([tag, [reg]]) => `           WHEN '${tag}' THEN (SELECT id FROM aircraft WHERE registration_number = '${reg}')`)
  .join('\n');
fs.writeFileSync(path.join(DIR, '17_uk_inventory.sql'), `-- UK per-flight hulls (generated by gen_uk_network.mjs). Expects
-- tmp_uk_flights(flight_id, flight_number) staged by seed_uk.sh. Additive:
-- only flights without inventory get a record. Run against skybook_inventory.

CREATE TEMP TABLE uk_hulls (flight_number varchar(10), hull varchar(6));
INSERT INTO uk_hulls VALUES
${hullRows};

WITH regs AS (
  SELECT h.flight_number,
         CASE h.hull
${regCase}
         END AS aircraft_id
  FROM uk_hulls h
)
INSERT INTO flight_inventory
  (created_at, updated_at, created_by, version, flight_id, aircraft_id, status,
   total_seats, available_seats, held_seats, reserved_seats, blocked_seats)
SELECT now(), now(), 'data-seed-uk', 0, t.flight_id, r.aircraft_id, 'OPEN',
  a.total_seats, a.total_seats, 0, 0, 0
FROM tmp_uk_flights t
JOIN regs r ON r.flight_number = t.flight_number
JOIN aircraft a ON a.id = r.aircraft_id
WHERE NOT EXISTS (SELECT 1 FROM flight_inventory fi WHERE fi.flight_id = t.flight_id);

SELECT a.registration_number, a.model, count(*) AS flights
FROM flight_inventory fi JOIN aircraft a ON a.id = fi.aircraft_id
WHERE fi.created_by = 'data-seed-uk'
GROUP BY 1, 2 ORDER BY 3 DESC;
`);

// ---- uk_airports.json -----------------------------------------------------
fs.writeFileSync(path.join(DIR, 'uk_airports.json'), JSON.stringify(
  AIRPORTS.map(([code, city, name, lat, lon]) => ({ code, city, name, lat, lon })), null, 2,
));

console.log('wrote 15_uk_fleet.sql, 16_uk_flights.sql, 17_uk_inventory.sql, uk_airports.json');
