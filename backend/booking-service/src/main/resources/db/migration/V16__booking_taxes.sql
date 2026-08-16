-- Government and airport taxes, charged at booking on top of the all-in
-- passenger fares (TaxPolicy): the booking-level total and the compact
-- per-code breakdown ("GB:216.00;UB:29.10;AE:16.30") the ticket itemises.
-- Nullable: bookings made before taxation read as untaxed, not as zero-tax
-- records pretending taxes were assessed.
ALTER TABLE bookings ADD COLUMN tax_total NUMERIC(10, 2);
ALTER TABLE bookings ADD COLUMN tax_breakdown VARCHAR(255);
