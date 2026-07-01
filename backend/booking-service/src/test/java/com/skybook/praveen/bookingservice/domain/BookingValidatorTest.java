package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingValidatorTest {

    private final BookingValidator bookingValidator = new BookingValidator();

    @Nested
    class ValidateCheckInAllowed {

        @Test
        void allowsCheckInForConfirmedAndPaidBooking() {
            Booking booking = Booking.builder()
                    .bookingStatus(BookingStatus.CONFIRMED)
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PAID).build())
                    .build();

            assertThatNoException().isThrownBy(() -> bookingValidator.validateCheckInAllowed(booking));
        }

        @Test
        void rejectsCheckInWhenBookingNotConfirmed() {
            Booking booking = Booking.builder()
                    .bookingStatus(BookingStatus.CREATED)
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PAID).build())
                    .build();

            assertThatThrownBy(() -> bookingValidator.validateCheckInAllowed(booking))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONFIRMED");
        }

        @Test
        void rejectsCheckInWhenPaymentNotCaptured() {
            Booking booking = Booking.builder()
                    .bookingStatus(BookingStatus.CONFIRMED)
                    .payment(BookingPayment.builder().paymentStatus(PaymentStatus.PENDING).build())
                    .build();

            assertThatThrownBy(() -> bookingValidator.validateCheckInAllowed(booking))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("payment");
        }

        @Test
        void rejectsCheckInWhenThereIsNoPaymentRecordAtAll() {
            Booking booking = Booking.builder()
                    .bookingStatus(BookingStatus.CONFIRMED)
                    .payment(null)
                    .build();

            assertThatThrownBy(() -> bookingValidator.validateCheckInAllowed(booking))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ValidateRefundAllowed {

        @Test
        void allowsRefundForCancelledBooking() {
            Booking booking = Booking.builder().bookingStatus(BookingStatus.CANCELLED).build();
            assertThatNoException().isThrownBy(() -> bookingValidator.validateRefundAllowed(booking));
        }

        @Test
        void rejectsRefundForNonCancelledBooking() {
            Booking booking = Booking.builder().bookingStatus(BookingStatus.CONFIRMED).build();
            assertThatThrownBy(() -> bookingValidator.validateRefundAllowed(booking))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ValidatePassportValidForTravel {

        @Test
        void allowsPassportExpiringAfterTravelDate() {
            Passenger passenger = Passenger.builder().firstName("Jane").lastName("Doe")
                    .passportExpiry(LocalDate.of(2030, 1, 1)).build();
            LocalDateTime departureTime = LocalDateTime.of(2026, 6, 1, 10, 0);

            assertThatNoException().isThrownBy(
                    () -> bookingValidator.validatePassportValidForTravel(passenger, departureTime));
        }

        @Test
        void rejectsPassportExpiringOnTravelDate() {
            LocalDateTime departureTime = LocalDateTime.of(2026, 6, 1, 10, 0);
            Passenger passenger = Passenger.builder().firstName("Jane").lastName("Doe")
                    .passportExpiry(LocalDate.of(2026, 6, 1)).build();

            assertThatThrownBy(() -> bookingValidator.validatePassportValidForTravel(passenger, departureTime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Jane Doe");
        }

        @Test
        void rejectsPassportExpiringBeforeTravelDate() {
            LocalDateTime departureTime = LocalDateTime.of(2026, 6, 1, 10, 0);
            Passenger passenger = Passenger.builder().firstName("Jane").lastName("Doe")
                    .passportExpiry(LocalDate.of(2026, 5, 1)).build();

            assertThatThrownBy(() -> bookingValidator.validatePassportValidForTravel(passenger, departureTime))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullPassportExpiry() {
            LocalDateTime departureTime = LocalDateTime.of(2026, 6, 1, 10, 0);
            Passenger passenger = Passenger.builder().firstName("Jane").lastName("Doe")
                    .passportExpiry(null).build();

            assertThatThrownBy(() -> bookingValidator.validatePassportValidForTravel(passenger, departureTime))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
