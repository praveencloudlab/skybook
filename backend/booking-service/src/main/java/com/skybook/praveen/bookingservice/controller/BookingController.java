package com.skybook.praveen.bookingservice.controller;

import com.skybook.praveen.bookingservice.dto.request.BookingSearchRequest;
import com.skybook.praveen.bookingservice.dto.request.CancelBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.CancelPassengersRequest;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.request.QuoteRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.dto.response.CancelPassengersResponse;
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
            summary = "Fare calendar",
            description = "Per-date lowest fares for a route and cabin over a capped range - powers the "
                    + "date-picker fare calendar. Public shopping data, like /quote."
    )
    @GetMapping("/fare-calendar")
    public List<com.skybook.praveen.bookingservice.dto.response.FareCalendarDayResponse> fareCalendar(
            @org.springframework.web.bind.annotation.RequestParam String originAirportCode,
            @org.springframework.web.bind.annotation.RequestParam String destinationAirportCode,
            @org.springframework.web.bind.annotation.RequestParam
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate startDate,
            @org.springframework.web.bind.annotation.RequestParam
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate endDate,
            @org.springframework.web.bind.annotation.RequestParam
            com.skybook.praveen.bookingservice.enums.TravelClass travelClass) {
        return bookingFacade.fareCalendar(originAirportCode, destinationAirportCode, startDate, endDate, travelClass);
    }

    @Operation(summary = "Watch a fare",
            description = "Fare watch: route + date + cabin, repriced hourly with the checkout calculator; "
                    + "the owner is mailed when the fare moves. Owner = the authenticated subject.")
    @org.springframework.web.bind.annotation.PostMapping("/fare-alerts")
    public com.skybook.praveen.bookingservice.dto.response.FareAlertResponse createFareAlert(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.skybook.praveen.bookingservice.dto.request.FareAlertRequest request) {
        return bookingFacade.createFareAlert(request.originAirportCode(), request.destinationAirportCode(),
                request.travelDate(), request.travelClass());
    }

    @Operation(summary = "My fare watches")
    @GetMapping("/fare-alerts")
    public List<com.skybook.praveen.bookingservice.dto.response.FareAlertResponse> myFareAlerts() {
        return bookingFacade.myFareAlerts();
    }

    @Operation(summary = "Stop watching a fare")
    @org.springframework.web.bind.annotation.DeleteMapping("/fare-alerts/{id}")
    public org.springframework.http.ResponseEntity<Void> deleteFareAlert(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        bookingFacade.deleteFareAlert(id);
        return org.springframework.http.ResponseEntity.noContent().build();
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
            summary = "Cancellation preview",
            description = "Live quote for cancelling this booking right now: the time-tier in force "
                    + "(100% / 50% / 0% refund), the tier deadlines for the charges chart, per-passenger "
                    + "refunds, and whether online cancellation is still open (it closes 2h before "
                    + "departure, and entirely once anyone has checked in)."
    )
    @GetMapping("/{id}/cancellation-preview")
    public com.skybook.praveen.bookingservice.dto.response.CancellationPreviewResponse cancellationPreview(
            @PathVariable Long id) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingFacade.cancellationPreview(id);
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

    @Operation(
            summary = "Cancel Passengers",
            description = "Cancel one or more selected passengers off a booking. The booking survives "
                    + "for the remaining passengers (status -> PARTIALLY_CANCELLED); only when the last "
                    + "passenger is cancelled does the booking become CANCELLED. A child/infant cannot be "
                    + "left without an adult. Seats are released and refunds calculated only for the "
                    + "cancelled passengers.")
    @PostMapping("/{id}/passengers/cancel")
    public CancelPassengersResponse cancelPassengers(
            @PathVariable Long id,
            @Valid @RequestBody CancelPassengersRequest request) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingFacade.cancelPassengers(id, request.bookingPassengerIds());
    }

    @Operation(summary = "Change seat",
            description = "Pre-check-in seat change: after payment, before check-in, under the same "
                    + "entitlement ceiling as check-in - Flexi/Premium move free, Saver up to the "
                    + "surcharge they paid. Stored fares never move.")
    @PostMapping("/{id}/passengers/{bookingPassengerId}/seat")
    public BookingResponse changeSeat(
            @PathVariable Long id,
            @PathVariable Long bookingPassengerId,
            @Valid @RequestBody com.skybook.praveen.bookingservice.dto.request.ChangeSeatRequest request) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingFacade.changeSeat(id, bookingPassengerId, request.seatNumber());
    }

    @Operation(summary = "Cancel Segment",
            description = "Cancel one whole segment of a multi-segment booking - \"drop the return\" "
                    + "(ROUND_TRIP_MODULE.md §7). Only segmentIndex >= 1 may be cancelled alone; the "
                    + "segment's seats release, its coupons go REFUNDED and the booking derives "
                    + "PARTIALLY_CANCELLED (or CANCELLED when nothing remains).")
    @PostMapping("/{id}/segments/{segmentIndex}/cancel")
    public CancelPassengersResponse cancelSegment(
            @PathVariable Long id,
            @PathVariable int segmentIndex) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingFacade.cancelSegment(id, segmentIndex);
    }

    @Operation(summary = "Rebook Segment (Premium date change)",
            description = "Move one segment onto a new flight, same booking and tickets "
                    + "(ROUND_TRIP_MODULE.md §11): old rows exchange (coupons CANCELLED), replacement "
                    + "rows price at the new departure, fare difference adjusts the payment snapshot. "
                    + "Premium fares only - other families use cancel + rebook.")
    @PostMapping("/{id}/segments/{segmentIndex}/rebook")
    public BookingResponse rebookSegment(
            @PathVariable Long id,
            @PathVariable int segmentIndex,
            @Valid @RequestBody com.skybook.praveen.bookingservice.dto.request.RebookSegmentRequest request) {
        accessGuard.requireOwnerOfBooking(id);
        return bookingFacade.rebookSegment(id, segmentIndex, request.newFlightId());
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
