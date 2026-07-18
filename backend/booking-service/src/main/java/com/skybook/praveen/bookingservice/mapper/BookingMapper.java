package com.skybook.praveen.bookingservice.mapper;

import com.skybook.praveen.bookingservice.dto.response.BookingContactResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPassengerResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingPaymentResponse;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingContact;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.BookingPayment;

public final class BookingMapper {

    private BookingMapper() {
    }

    public static BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getCustomerId(),
                booking.getFlightId(),
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

    public static BookingPassengerResponse toPassengerResponse(BookingPassenger bookingPassenger) {
        return new BookingPassengerResponse(
                bookingPassenger.getId(),
                bookingPassenger.getPassenger().getId(),
                bookingPassenger.getPassenger().getFirstName(),
                bookingPassenger.getPassenger().getLastName(),
                bookingPassenger.getPassenger().getPassportNumber(),
                bookingPassenger.getTravelClass(),
                bookingPassenger.getFareType(),
                bookingPassenger.getSeatNumber(),
                bookingPassenger.getBaseFare(),
                bookingPassenger.getSeatSurcharge(),
                bookingPassenger.getChargedSeatAssignmentMode(),
                bookingPassenger.getCurrency(),
                bookingPassenger.getFare(),
                bookingPassenger.getCheckInStatus()
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
