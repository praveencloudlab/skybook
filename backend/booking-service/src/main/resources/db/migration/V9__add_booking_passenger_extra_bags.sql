-- Ancillary baggage (booking-flow redesign): each passenger may buy extra
-- checked bags at booking time. The fee joins the passenger's fare breakdown
-- the same way the seat surcharge does: fare = base + seat + baggage, and
-- totalFare/payment/invoice all bill the stored fare - no recomputation.
ALTER TABLE booking_passengers
    ADD COLUMN IF NOT EXISTS extra_bags integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS baggage_fee numeric(19, 2) NOT NULL DEFAULT 0;
