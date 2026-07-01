package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Cross-machine invariants and business input rules that don't belong to
 * any single status enum's own transition table (docs section 4.4). Pure
 * logic, no I/O - throws on violation, doesn't mutate anything.
 */
@Component
public class BookingValidator {

    /**
     * "CheckInStatus may only advance past NOT_OPEN if BookingStatus =
     * CONFIRMED and PaymentStatus = PAID" (docs section 4.4).
     */
    public void validateCheckInAllowed(Booking booking) {

        if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Check-in is only allowed for CONFIRMED bookings");
        }

        if (booking.getPayment() == null || booking.getPayment().getPaymentStatus() != PaymentStatus.PAID) {
            throw new IllegalStateException("Check-in is only allowed once payment has been captured");
        }
    }

    /**
     * "PaymentStatus may only become REFUNDED if BookingStatus = CANCELLED"
     * (docs section 4.4).
     */
    public void validateRefundAllowed(Booking booking) {

        if (booking.getBookingStatus() != BookingStatus.CANCELLED) {
            throw new IllegalStateException("Refunds are only allowed for cancelled bookings");
        }
    }

    /** A passenger's passport must still be valid on the day they travel. */
    public void validatePassportValidForTravel(Passenger passenger, LocalDateTime flightDepartureTime) {

        if (passenger.getPassportExpiry() == null
                || !passenger.getPassportExpiry().isAfter(flightDepartureTime.toLocalDate())) {

            throw new IllegalArgumentException(
                    "Passenger " + passenger.getFirstName() + " " + passenger.getLastName()
                            + "'s passport must be valid beyond the travel date");
        }
    }
}
