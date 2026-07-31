-- The modern fleet (user request): A350-1000, 787-9 Dreamliner, A321XLR with
-- full cabin layouts - the SQL twin of the one-off admin-API run that first
-- created them, so a fresh install gets the same five-aircraft fleet.
-- Idempotent: skips any registration that already exists.

DO $$
DECLARE
  v_id bigint;
BEGIN
  -- ---------- G-SKYC · Airbus A350-1000 (333) ----------
  IF NOT EXISTS (SELECT 1 FROM aircraft WHERE registration_number = 'G-SKYC') THEN
    INSERT INTO aircraft (created_at, updated_at, created_by, version, registration_number, manufacturer, model, status, total_seats)
    VALUES (now(), now(), 'data-seed-fleet', 0, 'G-SKYC', 'Airbus', 'A350-1000', 'ACTIVE', 333)
    RETURNING id INTO v_id;

    -- Business 1-2-1 (rows 1-8)
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'BUSINESS', l.pos, false, 'ACTIVE'
    FROM generate_series(1, 8) r,
         (VALUES ('A','WINDOW'),('C','AISLE'),('D','AISLE'),('F','WINDOW')) AS l(letter, pos);
    -- Premium economy 2-4-2 (rows 9-13)
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'PREMIUM_ECONOMY', l.pos, false, 'ACTIVE'
    FROM generate_series(9, 13) r,
         (VALUES ('A','WINDOW'),('B','AISLE'),('C','AISLE'),('D','MIDDLE'),('E','MIDDLE'),('F','AISLE'),('G','AISLE'),('H','WINDOW')) AS l(letter, pos);
    -- Economy 3-3-3 (rows 14-42, exits 14 & 29)
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'ECONOMY', l.pos, r IN (14, 29), 'ACTIVE'
    FROM generate_series(14, 42) r,
         (VALUES ('A','WINDOW'),('B','MIDDLE'),('C','AISLE'),('D','AISLE'),('E','MIDDLE'),('F','AISLE'),('G','AISLE'),('H','MIDDLE'),('J','WINDOW')) AS l(letter, pos);
  END IF;

  -- ---------- G-SKYD · Boeing 787-9 Dreamliner (299) ----------
  IF NOT EXISTS (SELECT 1 FROM aircraft WHERE registration_number = 'G-SKYD') THEN
    INSERT INTO aircraft (created_at, updated_at, created_by, version, registration_number, manufacturer, model, status, total_seats)
    VALUES (now(), now(), 'data-seed-fleet', 0, 'G-SKYD', 'Boeing', '787-9 Dreamliner', 'ACTIVE', 299)
    RETURNING id INTO v_id;

    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'BUSINESS', l.pos, false, 'ACTIVE'
    FROM generate_series(1, 7) r,
         (VALUES ('A','WINDOW'),('C','AISLE'),('D','AISLE'),('F','WINDOW')) AS l(letter, pos);
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'PREMIUM_ECONOMY', l.pos, false, 'ACTIVE'
    FROM generate_series(8, 11) r,
         (VALUES ('A','WINDOW'),('B','AISLE'),('C','AISLE'),('D','MIDDLE'),('E','AISLE'),('F','AISLE'),('G','WINDOW')) AS l(letter, pos);
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'ECONOMY', l.pos, r IN (12, 26), 'ACTIVE'
    FROM generate_series(12, 38) r,
         (VALUES ('A','WINDOW'),('B','MIDDLE'),('C','AISLE'),('D','AISLE'),('E','MIDDLE'),('F','AISLE'),('G','AISLE'),('H','MIDDLE'),('J','WINDOW')) AS l(letter, pos);
  END IF;

  -- ---------- G-SKYE · Airbus A321XLR (202) ----------
  IF NOT EXISTS (SELECT 1 FROM aircraft WHERE registration_number = 'G-SKYE') THEN
    INSERT INTO aircraft (created_at, updated_at, created_by, version, registration_number, manufacturer, model, status, total_seats)
    VALUES (now(), now(), 'data-seed-fleet', 0, 'G-SKYE', 'Airbus', 'A321XLR', 'ACTIVE', 202)
    RETURNING id INTO v_id;

    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'BUSINESS', l.pos, false, 'ACTIVE'
    FROM generate_series(1, 4) r,
         (VALUES ('A','WINDOW'),('C','AISLE'),('D','AISLE'),('F','WINDOW')) AS l(letter, pos);
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'PREMIUM_ECONOMY', l.pos, false, 'ACTIVE'
    FROM generate_series(5, 8) r,
         (VALUES ('A','WINDOW'),('B','MIDDLE'),('C','AISLE'),('D','AISLE'),('E','MIDDLE'),('F','WINDOW')) AS l(letter, pos);
    INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, exit_row, status)
    SELECT now(), now(), 'data-seed-fleet', 0, v_id, r || l.letter, r, 'ECONOMY', l.pos, r IN (11, 12, 26), 'ACTIVE'
    FROM generate_series(9, 35) r,
         (VALUES ('A','WINDOW'),('B','MIDDLE'),('C','AISLE'),('D','AISLE'),('E','MIDDLE'),('F','WINDOW')) AS l(letter, pos);
  END IF;
END $$;
