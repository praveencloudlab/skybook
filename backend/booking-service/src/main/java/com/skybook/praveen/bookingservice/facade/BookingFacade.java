package com.skybook.praveen.bookingservice.facade;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.dto.request.CreateBookingRequest;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.bookingservice.producer.BookingEventProducer;
import com.skybook.praveen.bookingservice.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Orchestration layer (docs sections 2 and 8) - the only place that knows
 * other services/concerns exist: flight validation via FlightServiceClient,
 * persistence via BookingService, notifications via BookingEventProducer.
 *
 * Only wraps the three operations that actually need that orchestration
 * (create/confirm/cancel, each of which triggers a notification, and create
 * additionally validates the flight). Everything else - reads, search,
 * complete, check-in, board - has no cross-cutting concern to orchestrate,
 * so the controller calls BookingService directly for those. A Facade that
 * wrapped every single method with nothing to add would just be ceremony
 * (docs section 2's stated design principle).
 *
 * Deliberately NOT @Transactional: BookingService's individual methods are.
 * By the time a method here calls bookingService.xxx(...) and gets a
 * response back, that call's transaction has already committed - so
 * publishing to Kafka afterwards is equivalent to
 * @TransactionalEventListener(phase = AFTER_COMMIT) without the extra
 * indirection, appropriate at this project's scale (docs section 8/11 -
 * revisit with a transactional outbox if stronger delivery guarantees are
 * ever needed).
 */
@Component
@RequiredArgsConstructor
public class BookingFacade {

    private final FlightServiceClient flightServiceClient;
    private final BookingService bookingService;
    private final BookingEventProducer bookingEventProducer;

    public BookingResponse createBooking(CreateBookingRequest request) {

        FlightDetails flight = flightServiceClient.getFlight(request.flightId());

        if (flight.status() == FlightBookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot book a cancelled flight");
        }

        BookingResponse booking = bookingService.createBooking(request, flight.departureTime());

        bookingEventProducer.publishBookingCreated(booking);

        return booking;
    }

    public BookingResponse confirmBooking(Long id) {

        BookingResponse booking = bookingService.confirmBooking(id);

        bookingEventProducer.publishBookingConfirmed(booking);

        return booking;
    }

    public BookingResponse cancelBooking(Long id, String reason) {

        BookingResponse booking = bookingService.cancelBooking(id, reason);

        bookingEventProducer.publishBookingCancelled(booking);

        return booking;
    }
}
