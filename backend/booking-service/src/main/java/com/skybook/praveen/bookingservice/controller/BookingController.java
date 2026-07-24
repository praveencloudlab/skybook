package com.skybook.praveen.bookingservice.controller;

import com.skybook.praveen.bookingservice.dto.request.BookingSearchRequest;
import com.skybook.praveen.bookingservice.dto.request.CancelBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.QuoteRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.QuoteResponse;
import com.skybook.praveen.bookingservice.enums.BookingStatus;
import com.skybook.praveen.bookingservice.enums.PaymentStatus;
import com.skybook.praveen.bookingservice.facade.BookingFacade;
import com.skybook.praveen.bookingservice.service.BookingService;
import com.skybook.praveen.security.SecurityAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(
        name = "Booking Management",
        description = "Booking Service APIs for SkyBook Airline Reservation System"
)
public class BookingController {

    private final BookingFacade bookingFacade;
    private final BookingService bookingService;
    private final com.skybook.praveen.bookingservice.security.BookingAccessGuard accessGuard;

    @Operation(
            summary = "Create Booking",
            description = "Validates the flight, reserves seats, generates a PNR, and publishes a booking-created " +
                    "notification event. Payment is not collected here - see the confirm endpoint."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createBooking(@Valid @RequestBody CreateBookingRequest request) {
        return bookingFacade.createBooking(request);
    }

    @Operation(
            summary = "Quote Fares",
            description = "Fare options for one flight: which cabins it sells, seats left in each, and the cabin " +
                    "base fare per fare type - 'Economy from X, Business from Y'. A chosen seat adds its listed " +
                    "surcharge on top; auto-assigned seats add nothing."
    )
    @PostMapping("/quote")
    public QuoteResponse quoteFares(@Valid @RequestBody QuoteRequest request) {
        return bookingFacade.quoteFares(request.flightId());
    }

    @Operation(
            summary = "My Bookings",
            description = "Every booking belonging to the authenticated caller, newest first. "
                    + "This is the passenger-facing counterpart to the ADMIN-only list-all."
    )
    // Declared BEFORE /{id} so the literal path wins the mapping - otherwise
    // "mine" would be parsed as a booking id and fail with a 400.
    @GetMapping("/mine")
    public List<BookingResponse> getMyBookings() {
        // The subject comes from the validated token, never from the request, so
        // a caller cannot ask for anyone else's bookings: there is no id to
        // tamper with. That is why this needs no additional ownership check,
        // unlike /{id} below.
        return bookingService.getBookingsForOwner(SecurityAccess.currentSubject());
    }

    @Operation(summary = "Get Booking By Id")
    @GetMapping("/{id}")
    public BookingResponse getBookingById(@PathVariable Long id) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingService.getBookingById(id);
    }

    @Operation(summary = "Get Booking By Reference", description = "Looks up a booking by its PNR, e.g. SB8KF7.")
    @GetMapping("/reference/{pnr}")
    public BookingResponse getBookingByReference(@PathVariable String pnr) {
        accessGuard.requireOwnerOfBookingByReference(pnr);
        return bookingService.getBookingByReference(pnr);
    }

    @Operation(summary = "Get All Bookings")
    @GetMapping
    public List<BookingResponse> getAllBookings() {
        return bookingService.getAllBookings();
    }

    @Operation(
            summary = "Search Bookings",
            description = "Search by PNR, flight, passenger name, passport number, booking/payment status, " +
                    "booking date, contact email or phone. All parameters are optional filters."
    )
    @GetMapping("/search")
    public List<BookingResponse> searchBookings(
            @RequestParam(required = false) String bookingReference,
            @RequestParam(required = false) Long flightId,
            @RequestParam(required = false) String passengerName,
            @RequestParam(required = false) String passportNumber,
            @RequestParam(required = false) BookingStatus bookingStatus,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bookingDate,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone) {

        return bookingService.searchBookings(new BookingSearchRequest(
                bookingReference, flightId, passengerName, passportNumber,
                bookingStatus, paymentStatus, travelDate, bookingDate, email, phone));
    }

    @Operation(
            summary = "Confirm Booking",
            description = "v1: simulates successful payment directly (no Payment Service yet) and publishes a " +
                    "booking-confirmed notification event."
    )
    @PatchMapping("/{id}/confirm")
    public BookingResponse confirmBooking(@PathVariable Long id) {
        return bookingFacade.confirmBooking(id);
    }

    @Operation(
            summary = "Cancel Booking",
            description = "Cancels the booking, closes check-in for every passenger on it, refunds if payment had " +
                    "been captured, and publishes a booking-cancelled notification event."
    )
    @PatchMapping("/{id}/cancel")
    public BookingResponse cancelBooking(
            @PathVariable Long id,
            @RequestBody(required = false) CancelBookingRequest request) {
        accessGuard.requireOwnerOfBooking(id);
        String reason = request != null ? request.reason() : null;
        return bookingFacade.cancelBooking(id, reason);
    }

    @Operation(summary = "Complete Booking", description = "Marks the booking as COMPLETED once the flight has flown.")
    @PatchMapping("/{id}/complete")
    public BookingResponse completeBooking(@PathVariable Long id) {
        return bookingService.completeBooking(id);
    }

    @Operation(
            summary = "Check In Passenger",
            description = "Check-in is per passenger, not per booking - passengerId here identifies that " +
                    "passenger's line item within this specific booking."
    )
    @PatchMapping("/{id}/passengers/{passengerId}/check-in")
    public BookingResponse checkInPassenger(@PathVariable Long id, @PathVariable Long passengerId) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingService.checkInPassenger(id, passengerId);
    }

    @Operation(summary = "Board Passenger")
    @PatchMapping("/{id}/passengers/{passengerId}/board")
    public BookingResponse boardPassenger(@PathVariable Long id, @PathVariable Long passengerId) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingService.boardPassenger(id, passengerId);
    }
}
