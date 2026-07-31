-- Real terminals on the check-in snapshot and the boarding pass (the
-- terminals feature): check_ins snapshots both ends from the booking event;
-- the boarding pass carries the DEPARTURE terminal - the one printed on real
-- passes, the first thing a passenger looks for at the airport.
--
-- Backfill derives the carrier from the flight number prefix using the same
-- assignment rules as flight-service's TerminalPolicy / seed 08_terminals.sql.

ALTER TABLE check_ins ADD COLUMN departure_terminal varchar(4);
ALTER TABLE check_ins ADD COLUMN arrival_terminal varchar(4);
ALTER TABLE boarding_passes ADD COLUMN departure_terminal varchar(4);

CREATE OR REPLACE FUNCTION sb_terminal(airline text, airport text) RETURNS text AS $$
SELECT CASE airport
  WHEN 'LHR' THEN CASE
      WHEN airline = 'BA' THEN '5'
      WHEN airline IN ('EK', 'VS') THEN '3'
      WHEN airline IN ('EY', 'QR', 'AF', 'KL') THEN '4'
      ELSE '2' END
  WHEN 'DXB' THEN CASE WHEN airline = 'EK' THEN '3' ELSE '1' END
  WHEN 'CDG' THEN CASE WHEN airline = 'AF' THEN '2E' ELSE '1' END
  WHEN 'FRA' THEN CASE WHEN airline = 'LH' THEN '1' ELSE '2' END
  WHEN 'JFK' THEN CASE
      WHEN airline = 'BA' THEN '8'
      WHEN airline IN ('EK', 'DL') THEN '4'
      ELSE '1' END
  WHEN 'SIN' THEN CASE WHEN airline = 'SQ' THEN '3' ELSE '1' END
  WHEN 'AUH' THEN 'A'
  WHEN 'JNB' THEN 'A'
  WHEN 'ATL' THEN 'I'
  WHEN 'DEL' THEN '3'
  WHEN 'BOM' THEN '2'
  WHEN 'MAN' THEN '2'
  ELSE '1'
END
$$ LANGUAGE sql IMMUTABLE;

UPDATE check_ins
SET departure_terminal = sb_terminal(substring(flight_number from 1 for 2), origin_airport_code)
WHERE departure_terminal IS NULL AND flight_number IS NOT NULL AND origin_airport_code IS NOT NULL;

UPDATE check_ins
SET arrival_terminal = sb_terminal(substring(flight_number from 1 for 2), destination_airport_code)
WHERE arrival_terminal IS NULL AND flight_number IS NOT NULL AND destination_airport_code IS NOT NULL;

UPDATE boarding_passes
SET departure_terminal = sb_terminal(substring(flight_number from 1 for 2), origin_airport_code)
WHERE departure_terminal IS NULL AND flight_number IS NOT NULL AND origin_airport_code IS NOT NULL;
