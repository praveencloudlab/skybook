package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingHistory;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.enums.BookingHistoryField;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies transitions for all three independent state
 * machines (docs section 4), and records every transition onto
 * {@code Booking.history} in-memory.
 *
 * Deliberately has no repository/Spring-context dependency beyond
 * {@code @Component} - it only mutates the entities handed to it and
 * appends plain {@link BookingHistory} objects, which get persisted for free
 * via Booking's cascade when the caller eventually saves the aggregate. That
 * keeps this class trivially unit-testable (plain objects, no mocks) while
 * still guaranteeing every transition is recorded.
 */
@Component
public class BookingStateMachine {

    private static final Map<BookingStatus, Set<BookingStatus>> BOOKING_TRANSITIONS = new EnumMap<>(BookingStatus.class);
    private static final Map<PaymentStatus, Set<PaymentStatus>> PAYMENT_TRANSITIONS = new EnumMap<>(PaymentStatus.class);
    private static final Map<CheckInStatus, Set<CheckInStatus>> CHECK_IN_TRANSITIONS = new EnumMap<>(CheckInStatus.class);

    static {
        BOOKING_TRANSITIONS.put(BookingStatus.CREATED, EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED));
        BOOKING_TRANSITIONS.put(BookingStatus.CONFIRMED, EnumSet.of(BookingStatus.CANCELLED, BookingStatus.COMPLETED));
        BOOKING_TRANSITIONS.put(BookingStatus.CANCELLED, EnumSet.noneOf(BookingStatus.class));
        BOOKING_TRANSITIONS.put(BookingStatus.COMPLETED, EnumSet.noneOf(BookingStatus.class));

        PAYMENT_TRANSITIONS.put(PaymentStatus.PENDING, EnumSet.of(PaymentStatus.PAID, PaymentStatus.FAILED));
        PAYMENT_TRANSITIONS.put(PaymentStatus.FAILED, EnumSet.of(PaymentStatus.PENDING));
        PAYMENT_TRANSITIONS.put(PaymentStatus.PAID, EnumSet.of(PaymentStatus.REFUNDED));
        PAYMENT_TRANSITIONS.put(PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentStatus.class));

        CHECK_IN_TRANSITIONS.put(CheckInStatus.NOT_OPEN, EnumSet.of(CheckInStatus.OPEN, CheckInStatus.CLOSED));
        CHECK_IN_TRANSITIONS.put(CheckInStatus.OPEN, EnumSet.of(CheckInStatus.CHECKED_IN, CheckInStatus.NO_SHOW, CheckInStatus.CLOSED));
        CHECK_IN_TRANSITIONS.put(CheckInStatus.CHECKED_IN, EnumSet.of(CheckInStatus.BOARDED, CheckInStatus.NO_SHOW, CheckInStatus.CLOSED));
        CHECK_IN_TRANSITIONS.put(CheckInStatus.BOARDED, EnumSet.of(CheckInStatus.CLOSED));
        CHECK_IN_TRANSITIONS.put(CheckInStatus.NO_SHOW, EnumSet.of(CheckInStatus.CLOSED));
        CHECK_IN_TRANSITIONS.put(CheckInStatus.CLOSED, EnumSet.noneOf(CheckInStatus.class));
    }

    // ---------------------------------------------------------------
    // BookingStatus
    // ---------------------------------------------------------------

    public boolean canTransitionBooking(BookingStatus from, BookingStatus to) {
        return BOOKING_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Applies a BookingStatus transition, records it in history, and - if
     * moving to CANCELLED - cascades every passenger's CheckInStatus to
     * CLOSED (docs section 4.4: "can't check in a cancelled booking").
     */
    public void transitionBookingStatus(Booking booking, BookingStatus to, String reason, String changedBy) {

        BookingStatus from = booking.getBookingStatus();

        if (!canTransitionBooking(from, to)) {
            throw new IllegalStateException("Cannot transition booking status from " + from + " to " + to);
        }

        booking.setBookingStatus(to);
        recordHistory(booking, BookingHistoryField.BOOKING_STATUS, from, to, reason, changedBy);

        if (to == BookingStatus.CANCELLED) {
            for (BookingPassenger passenger : booking.getPassengers()) {
                if (canTransitionCheckIn(passenger.getCheckInStatus(), CheckInStatus.CLOSED)) {
                    transitionCheckInStatus(passenger, CheckInStatus.CLOSED, changedBy);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // PaymentStatus
    // ---------------------------------------------------------------

    public boolean canTransitionPayment(PaymentStatus from, PaymentStatus to) {
        return PAYMENT_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void transitionPaymentStatus(BookingPayment payment, PaymentStatus to, String changedBy) {

        PaymentStatus from = payment.getPaymentStatus();

        if (!canTransitionPayment(from, to)) {
            throw new IllegalStateException("Cannot transition payment status from " + from + " to " + to);
        }

        payment.setPaymentStatus(to);

        if (to == PaymentStatus.PAID) {
            payment.setPaidAt(LocalDateTime.now());
        }

        recordHistory(payment.getBooking(), BookingHistoryField.PAYMENT_STATUS, from, to, null, changedBy);
    }

    // ---------------------------------------------------------------
    // CheckInStatus
    // ---------------------------------------------------------------

    public boolean canTransitionCheckIn(CheckInStatus from, CheckInStatus to) {
        return CHECK_IN_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void transitionCheckInStatus(BookingPassenger passenger, CheckInStatus to, String changedBy) {

        CheckInStatus from = passenger.getCheckInStatus();

        if (!canTransitionCheckIn(from, to)) {
            throw new IllegalStateException("Cannot transition check-in status from " + from + " to " + to);
        }

        passenger.setCheckInStatus(to);
        recordHistory(passenger.getBooking(), BookingHistoryField.CHECK_IN_STATUS, from, to, null, changedBy);
    }

    private void recordHistory(Booking booking, BookingHistoryField field, Object from, Object to, String reason, String changedBy) {

        booking.getHistory().add(BookingHistory.builder()
                .booking(booking)
                .fieldChanged(field)
                .fromValue(String.valueOf(from))
                .toValue(String.valueOf(to))
                .changedAt(LocalDateTime.now())
                .changedBy(changedBy)
                .reason(reason)
                .build());
    }
}
