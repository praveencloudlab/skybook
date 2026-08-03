-- Real terminal assignments for every scheduled flight - the SQL mirror of
-- flight-service's TerminalPolicy.java (keep the two in sync). Runs LAST in
-- every seeding path and is idempotent: it only fills rows whose terminals
-- are still NULL, so Java-created flights (which set their own via the
-- policy) are never overwritten.
--
-- Columns are created by flight-service (ddl-auto) - guard for a seed run
-- against a database whose service hasn't started on the new build yet.
ALTER TABLE flights ADD COLUMN IF NOT EXISTS departure_terminal varchar(4);
ALTER TABLE flights ADD COLUMN IF NOT EXISTS arrival_terminal varchar(4);

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
  ELSE '1'  -- DOH, IST, SYD, HKG, EDI, GLA, BHX, NBO: single passenger terminal
END
$$ LANGUAGE sql IMMUTABLE;

UPDATE flights
SET departure_terminal = sb_terminal(airline_code, origin_airport_code)
WHERE departure_terminal IS NULL;

UPDATE flights
SET arrival_terminal = sb_terminal(airline_code, destination_airport_code)
WHERE arrival_terminal IS NULL;
