package com.skybook.praveen.bookingservice.mapper;

import com.skybook.praveen.bookingservice.dto.response.BookingContactResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPaymentResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingSegmentResponse;
import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingContact;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;
import com.skybook.praveen.bookingservice.entity.BookingSegment;
import com.skybook.praveen.bookingservice.enums.CheckInStatus;

import java.util.List;

public final class BookingMapper {

    private BookingMapper() {
    }

    public static BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getCustomerId(),
                booking.getFlightId(),
                booking.getSegments().stream()
                        .map(segment -> toSegmentResponse(booking, segment))
                        .toList(),
                booking.getBookingStatus(),
                booking.getBookingDate(),
                booking.getTotalFare(),
                booking.getRemarks(),
                booking.getOwnerSubject(),
                booking.getPassengers().stream().map(BookingMapper::toPassengerResponse).toList(),
                booking.getContact() != null ? toContactResponse(booking.getContact()) : null,
                booking.getPayment() != null ? toPaymentResponse(booking.getPayment()) : null,
                booking.getCreatedBy(),
                booking.getUpdatedBy(),
                booking.getVersion(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }

    /**
     * Derived, never stored (ROUND_TRIP_MODULE.md §3): CANCELLED when every
     * row on the leg is cancelled, CHECKED_IN when any active row is, else
     * UPCOMING. FLOWN is the frontend's call - it has the departure time.
     */
    private static BookingSegmentResponse toSegmentResponse(Booking booking, BookingSegment segment) {
        List<BookingPassenger> rows = booking.getPassengers().stream()
                .filter(p -> isOnSegment(p, segment))
                .toList();
        String status;
        if (!rows.isEmpty() && rows.stream().allMatch(BookingPassenger::isCancelled)) {
            status = "CANCELLED";
        } else if (rows.stream().anyMatch(p -> !p.isCancelled()
                && p.getCheckInStatus() == CheckInStatus.CHECKED_IN)) {
            status = "CHECKED_IN";
        } else {
            status = "UPCOMING";
        }
        return new BookingSegmentResponse(segment.getId(), segment.getSegmentIndex(),
                segment.getFlightId(), status);
    }

    // Reference equality covers a not-yet-flushed aggregate (ids still null);
    // id equality covers rows reloaded from the database.
    private static boolean isOnSegment(BookingPassenger row, BookingSegment segment) {
        return row.getSegment() == segment
                || (row.getSegment() != null && row.getSegment().getId() != null
                    && row.getSegment().getId().equals(segment.getId()));
    }

    public static BookingPassengerResponse toPassengerResponse(BookingPassenger bookingPassenger) {
        return new BookingPassengerResponse(
                bookingPassenger.getId(),
                bookingPassenger.getPassenger().getId(),
                bookingPassenger.getSegment() != null ? bookingPassenger.getSegment().getSegmentIndex() : 0,
                bookingPassenger.getPassenger().getFirstName(),
                bookingPassenger.getPassenger().getLastName(),
                bookingPassenger.getPassenger().getPassportNumber(),
                bookingPassenger.getPassenger().getTitle(),
                bookingPassenger.getPassenger().getGender(),
                bookingPassenger.getPassenger().getDob(),
                bookingPassenger.getPassenger().getNationality(),
                bookingPassenger.getPassenger().getPassportExpiry(),
                bookingPassenger.getTravelClass(),
                bookingPassenger.getFareType(),
                bookingPassenger.getSeatNumber(),
                bookingPassenger.getBaseFare(),
                bookingPassenger.getSeatSurcharge(),
                bookingPassenger.getExtraBags(),
                bookingPassenger.getBaggageFee(),
                bookingPassenger.getChargedSeatAssignmentMode(),
                bookingPassenger.getCurrency(),
                bookingPassenger.getFare(),
                bookingPassenger.getCheckInStatus(),
                bookingPassenger.isCancelled(),
                com.skybook.praveen.bookingservice.domain.PassengerCategory
                        .of(bookingPassenger.getPassenger().getDob(), java.time.LocalDate.now())
                        .name()
        );
    }

    public static BookingContactResponse toContactResponse(BookingContact contact) {
        return new BookingContactResponse(
                contact.getContactName(),
                contact.getContactEmail(),
                contact.getContactPhone()
        );
    }

    public static BookingPaymentResponse toPaymentResponse(BookingPayment payment) {
        return new BookingPaymentResponse(
                payment.getPaymentStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getExternalPaymentReference(),
                payment.getPaidAt()
        );
    }
}
