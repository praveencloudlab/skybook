-- The airline whose "plate" the ticket is issued on (first marketing carrier
-- of the journey) - drives the IATA ticket-stock prefix (EK 176, BA 125...).
-- Nullable: pre-V17 bookings keep the default SkyBook stock.
ALTER TABLE bookings ADD COLUMN validating_airline VARCHAR(2);
