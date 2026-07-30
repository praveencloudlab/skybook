-- Through-ticketing (ROUND_TRIP_MODULE.md extension): a booking's segments
-- can now be CONNECTION legs of one direction (same-carrier through-ticket),
-- not only outbound + return. direction records which journey direction a
-- segment belongs to: 0 = outbound, 1 = return.
--
-- Rules that used segment_index as a proxy for "the return" key on direction
-- instead: lone-segment cancellation is allowed only for direction 1 (you
-- can drop the return, never half an outbound connection), and baggage fees
-- charge once per DIRECTION, not per leg.
--
-- Backfill: every existing multi-segment booking is a round trip (segment 1
-- = the return); connections did not exist before this migration.
ALTER TABLE booking_segments ADD COLUMN direction int NOT NULL DEFAULT 0;
UPDATE booking_segments SET direction = 1 WHERE segment_index > 0;
