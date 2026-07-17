package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.enums.BookingHistoryField;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingStateMachineTest {

    private final BookingStateMachine bookingStateMachine = new BookingStateMachine();

    private Booking bookingWith(BookingStatus status) {
        Booking booking = Booking.builder().id(1L).bookingStatus(status).build();
        booking.setPassengers(new ArrayList<>());
        booking.setHistory(new ArrayList<>());
        return booking;
    }

    // ---------------------------------------------------------------
    // BookingStatus
    // ---------------------------------------------------------------

    @Nested
    class BookingStatusTransitions {

        // The full golden transition table (docs section 4.1 + seat-selection
        // §5.1a DRAFT lifecycle) - every (from, to) pair not listed here is
        // expected to be invalid. In particular DRAFT -> CONFIRMED is illegal:
        // a crash-orphaned draft can never be confirmed.
        private final Map<BookingStatus, Set<BookingStatus>> validTransitions = Map.of(
                BookingStatus.DRAFT, Set.of(BookingStatus.CREATED, BookingStatus.CANCELLED),
                BookingStatus.CREATED, Set.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED),
                BookingStatus.CONFIRMED, Set.of(BookingStatus.CANCELLED, BookingStatus.COMPLETED),
                BookingStatus.CANCELLED, Set.of(),
                BookingStatus.COMPLETED, Set.of()
        );

        @Test
        void matchesTheFullGoldenTransitionTable() {
            for (BookingStatus from : BookingStatus.values()) {
                for (BookingStatus to : BookingStatus.values()) {
                    boolean expected = validTransitions.get(from).contains(to);
                    assertThat(bookingStateMachine.canTransitionBooking(from, to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        void appliesAValidTransitionAndRecordsHistory() {
            Booking booking = bookingWith(BookingStatus.CREATED);

            bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CONFIRMED, "paid", "agent1");

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getHistory()).hasSize(1);
            assertThat(booking.getHistory().get(0).getFieldChanged()).isEqualTo(BookingHistoryField.BOOKING_STATUS);
            assertThat(booking.getHistory().get(0).getFromValue()).isEqualTo("CREATED");
            assertThat(booking.getHistory().get(0).getToValue()).isEqualTo("CONFIRMED");
            assertThat(booking.getHistory().get(0).getReason()).isEqualTo("paid");
            assertThat(booking.getHistory().get(0).getChangedBy()).isEqualTo("agent1");
        }

        @Test
        void rejectsAnInvalidTransitionAndRecordsNoHistory() {
            Booking booking = bookingWith(BookingStatus.CANCELLED);

            assertThatThrownBy(() -> bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CONFIRMED, null, "agent1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CANCELLED")
                    .hasMessageContaining("CONFIRMED");

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getHistory()).isEmpty();
        }

        @Test
        void cancellingABookingClosesCheckInForEveryPassenger() {
            Booking booking = bookingWith(BookingStatus.CONFIRMED);

            BookingPassenger openPassenger = BookingPassenger.builder().booking(booking).checkInStatus(CheckInStatus.OPEN).build();
            BookingPassenger checkedInPassenger = BookingPassenger.builder().booking(booking).checkInStatus(CheckInStatus.CHECKED_IN).build();
            List<BookingPassenger> passengers = new ArrayList<>(List.of(openPassenger, checkedInPassenger));
            booking.setPassengers(passengers);

            bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CANCELLED, "route discontinued", "agent1");

            assertThat(openPassenger.getCheckInStatus()).isEqualTo(CheckInStatus.CLOSED);
            assertThat(checkedInPassenger.getCheckInStatus()).isEqualTo(CheckInStatus.CLOSED);
            // 1 booking-status entry + 2 check-in-status entries (one per passenger).
            assertThat(booking.getHistory()).hasSize(3);
        }

        @Test
        void cancellingDoesNotErrorWhenAPassengerIsAlreadyClosed() {
            Booking booking = bookingWith(BookingStatus.CONFIRMED);

            BookingPassenger alreadyClosed = BookingPassenger.builder().booking(booking).checkInStatus(CheckInStatus.CLOSED).build();
            booking.setPassengers(new ArrayList<>(List.of(alreadyClosed)));

            bookingStateMachine.transitionBookingStatus(booking, BookingStatus.CANCELLED, null, "agent1");

            assertThat(alreadyClosed.getCheckInStatus()).isEqualTo(CheckInStatus.CLOSED);
            // Only the booking-status change is recorded - CLOSED -> CLOSED isn't a real transition.
            assertThat(booking.getHistory()).hasSize(1);
        }
    }

    // ---------------------------------------------------------------
    // PaymentStatus
    // ---------------------------------------------------------------

    @Nested
    class PaymentStatusTransitions {

        private final Map<PaymentStatus, Set<PaymentStatus>> validTransitions = Map.of(
                PaymentStatus.PENDING, Set.of(PaymentStatus.PAID, PaymentStatus.FAILED),
                PaymentStatus.FAILED, Set.of(PaymentStatus.PENDING),
                PaymentStatus.PAID, Set.of(PaymentStatus.REFUNDED),
                PaymentStatus.REFUNDED, Set.of()
        );

        @Test
        void matchesTheFullGoldenTransitionTable() {
            for (PaymentStatus from : PaymentStatus.values()) {
                for (PaymentStatus to : PaymentStatus.values()) {
                    boolean expected = validTransitions.get(from).contains(to);
                    assertThat(bookingStateMachine.canTransitionPayment(from, to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        void movingToPaidStampsPaidAt() {
            Booking booking = bookingWith(BookingStatus.CREATED);
            BookingPayment payment = BookingPayment.builder().booking(booking).paymentStatus(PaymentStatus.PENDING).build();
            booking.setPayment(payment);

            bookingStateMachine.transitionPaymentStatus(payment, PaymentStatus.PAID, "agent1");

            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getPaidAt()).isNotNull();
            assertThat(booking.getHistory()).hasSize(1);
            assertThat(booking.getHistory().get(0).getFieldChanged()).isEqualTo(BookingHistoryField.PAYMENT_STATUS);
        }

        @Test
        void rejectsSkippingStraightFromPendingToRefunded() {
            Booking booking = bookingWith(BookingStatus.CREATED);
            BookingPayment payment = BookingPayment.builder().booking(booking).paymentStatus(PaymentStatus.PENDING).build();
            booking.setPayment(payment);

            assertThatThrownBy(() -> bookingStateMachine.transitionPaymentStatus(payment, PaymentStatus.REFUNDED, "agent1"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ---------------------------------------------------------------
    // CheckInStatus
    // ---------------------------------------------------------------

    @Nested
    class CheckInStatusTransitions {

        private final Map<CheckInStatus, Set<CheckInStatus>> validTransitions = Map.of(
                CheckInStatus.NOT_OPEN, Set.of(CheckInStatus.OPEN, CheckInStatus.CLOSED),
                CheckInStatus.OPEN, Set.of(CheckInStatus.CHECKED_IN, CheckInStatus.NO_SHOW, CheckInStatus.CLOSED),
                CheckInStatus.CHECKED_IN, Set.of(CheckInStatus.BOARDED, CheckInStatus.NO_SHOW, CheckInStatus.CLOSED),
                CheckInStatus.BOARDED, Set.of(CheckInStatus.CLOSED),
                CheckInStatus.NO_SHOW, Set.of(CheckInStatus.CLOSED),
                CheckInStatus.CLOSED, Set.of()
        );

        @Test
        void matchesTheFullGoldenTransitionTable() {
            for (CheckInStatus from : CheckInStatus.values()) {
                for (CheckInStatus to : CheckInStatus.values()) {
                    boolean expected = validTransitions.get(from).contains(to);
                    assertThat(bookingStateMachine.canTransitionCheckIn(from, to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        void appliesAValidTransitionAndRecordsHistoryOnTheOwningBooking() {
            Booking booking = bookingWith(BookingStatus.CONFIRMED);
            BookingPassenger passenger = BookingPassenger.builder().booking(booking).checkInStatus(CheckInStatus.OPEN).build();
            booking.setPassengers(new ArrayList<>(List.of(passenger)));

            bookingStateMachine.transitionCheckInStatus(passenger, CheckInStatus.CHECKED_IN, "agent1");

            assertThat(passenger.getCheckInStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
            assertThat(booking.getHistory()).hasSize(1);
            assertThat(booking.getHistory().get(0).getFieldChanged()).isEqualTo(BookingHistoryField.CHECK_IN_STATUS);
        }

        @Test
        void rejectsBoardingAPassengerWhoNeverCheckedIn() {
            Booking booking = bookingWith(BookingStatus.CONFIRMED);
            BookingPassenger passenger = BookingPassenger.builder().booking(booking).checkInStatus(CheckInStatus.NOT_OPEN).build();
            booking.setPassengers(new ArrayList<>(List.of(passenger)));

            assertThatThrownBy(() -> bookingStateMachine.transitionCheckInStatus(passenger, CheckInStatus.BOARDED, "agent1"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
