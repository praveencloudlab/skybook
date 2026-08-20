package com.skybook.praveen.flightservice.service.impl;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.RouteCalendarDayResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.exception.FlightNotFoundException;
import com.skybook.praveen.flightservice.mapper.FlightMapper;
import com.skybook.praveen.flightservice.repository.FlightRepository;
import com.skybook.praveen.flightservice.service.FlightService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FlightServiceImpl implements FlightService {

    // Public shopping cutoff: booking closes 60 minutes before scheduled
    // departure (check-in shuts at 45). Applied to itineraries and the route
    // calendar - never to admin reads, which must see departed flights.
    static final long BOOKING_CUTOFF_MINUTES = 60;

    private final FlightRepository flightRepository;

    /**
     * Ops/backdating switch (default OFF): when true, public shopping also
     * lists flights that have already departed, so a backdated booking can be
     * entered. Pair with booking-service's {@code booking.allow-past-bookings}
     * - listing a past flight is pointless if checkout still refuses it.
     * Never enable on a public deployment.
     */
    private final boolean showPastFlights;

    public FlightServiceImpl(FlightRepository flightRepository,
            @org.springframework.beans.factory.annotation.Value("${flight.show-past-flights:false}") boolean showPastFlights) {
        this.flightRepository = flightRepository;
        this.showPastFlights = showPastFlights;
    }

    @Override
    public FlightResponse createFlight(CreateFlightRequest request) {

        validateFlightCreation(request);

        Flight flight = FlightMapper.toEntity(request);

        Flight savedFlight = flightRepository.save(flight);

        return FlightMapper.toResponse(savedFlight);
    }

    @Override
    public List<FlightResponse> createFlights(List<CreateFlightRequest> requests) {

        requests.forEach(this::validateFlightCreation);

        List<Flight> flights = requests.stream()
                .map(FlightMapper::toEntity)
                .toList();

        List<Flight> savedFlights = flightRepository.saveAll(flights);

        return savedFlights.stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public FlightResponse getFlightById(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        return FlightMapper.toResponse(flight);
    }

    @Override
    public org.springframework.data.domain.Page<FlightResponse> getAllFlights(
            org.springframework.data.domain.Pageable pageable) {
        // findAll(Pageable) issues LIMIT/OFFSET + a count - one page of rows,
        // never the whole ~920k table into memory.
        return flightRepository.findAll(pageable).map(FlightMapper::toResponse);
    }

    @Override
    public FlightResponse updateFlight(Long id,
                                       UpdateFlightRequest request) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        if (!request.arrivalTime().isAfter(request.departureTime())) {
            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        if (request.originAirportCode()
                .equalsIgnoreCase(request.destinationAirportCode())) {

            throw new IllegalArgumentException(
                    "Origin and destination airports must be different");
        }

        FlightMapper.updateEntity(flight, request);

        Flight updatedFlight = flightRepository.save(flight);

        return FlightMapper.toResponse(updatedFlight);
    }

    @Override
    public void deleteFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flightRepository.delete(flight);
    }

    @Override
    public void restoreFlight(Long id) {

        throw new UnsupportedOperationException(
                "Restore Flight will be implemented with Soft Delete.");
    }

    private void validateFlightCreation(CreateFlightRequest request) {

        // Flight numbers are not globally unique - the same number can recur
        // daily under a schedule. Uniqueness is per (flightNumber, departureTime).
        if (flightRepository.existsByFlightNumberAndDepartureTime(
                request.flightNumber().toUpperCase(),
                request.departureTime())) {

            throw new IllegalArgumentException(
                    "Flight " + request.flightNumber()
                            + " already exists for departure time " + request.departureTime());
        }

        if (!request.arrivalTime().isAfter(request.departureTime())) {

            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        if (request.originAirportCode()
                .equalsIgnoreCase(request.destinationAirportCode())) {

            throw new IllegalArgumentException(
                    "Origin and destination airports must be different");
        }
    }

    @Override
    public List<FlightResponse> searchFlights(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate departureDate) {

        LocalDateTime start = departureDate.atStartOfDay();
        LocalDateTime end = departureDate.plusDays(1)
                .atStartOfDay()
                .minusNanos(1);

        return flightRepository
                .findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                        originAirportCode.toUpperCase(),
                        destinationAirportCode.toUpperCase(),
                        start,
                        end)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    // A same-carrier junction is a PROTECTED connection (the airline owns
    // the transfer and checks bags through), so it needs less slack than a
    // self-transfer where the passenger collects and re-checks bags. Shared
    // by itineraries AND the route calendar - the calendar must light
    // exactly the days a search would satisfy, so they must agree.
    private static final long MIN_LAYOVER_SAME_CARRIER = 45;
    private static final long MIN_LAYOVER_SELF_TRANSFER = 60;
    private static final long MAX_LAYOVER_MINUTES = 420;  // 7h - beyond this nobody calls it a connection

    @Override
    public List<com.skybook.praveen.flightservice.dto.response.ItineraryResponse> getItineraries(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate departureDate) {

        String origin = originAirportCode.toUpperCase();
        String destination = destinationAirportCode.toUpperCase();

        // Public shopping must not offer what can no longer be bought: a leg
        // that has departed, or departs within the booking cutoff (booking
        // closes 60 minutes before scheduled departure - check-in desks shut
        // at 45). Admin's /search stays unfiltered on purpose: back-office
        // needs to see today's departed flights to run them.
        LocalDateTime bookableFrom = showPastFlights
                ? LocalDateTime.MIN
                : LocalDateTime.now().plusMinutes(BOOKING_CUTOFF_MINUTES);

        // One window covers every possible leg: first legs depart on the
        // requested date; onward legs may run into the next day (overnight
        // arrivals), and a second connection the day after that.
        List<Flight> window = flightRepository.findByDepartureTimeBetween(
                        departureDate.atStartOfDay(),
                        departureDate.plusDays(2).atStartOfDay())
                .stream()
                .filter(f -> f.getStatus() != FlightStatus.CANCELLED)
                .filter(f -> f.getDepartureTime().isAfter(bookableFrom))
                .toList();

        java.util.Map<String, List<Flight>> byOrigin = new java.util.HashMap<>();
        for (Flight f : window) {
            byOrigin.computeIfAbsent(f.getOriginAirportCode(), k -> new java.util.ArrayList<>()).add(f);
        }

        long minLayoverSameCarrier = MIN_LAYOVER_SAME_CARRIER;
        long minLayoverSelfTransfer = MIN_LAYOVER_SELF_TRANSFER;
        long maxLayover = MAX_LAYOVER_MINUTES;
        long maxTotal = 40L * 60;

        List<com.skybook.praveen.flightservice.dto.response.ItineraryResponse> results = new java.util.ArrayList<>();

        java.util.function.BiFunction<Flight, Flight, Long> layover = (a, b) ->
                java.time.Duration.between(a.getArrivalTime(), b.getDepartureTime()).toMinutes();
        java.util.function.BiFunction<Flight, Flight, Long> minLayover = (a, b) ->
                a.getAirlineCode().equals(b.getAirlineCode()) ? minLayoverSameCarrier : minLayoverSelfTransfer;

        for (Flight first : byOrigin.getOrDefault(origin, List.of())) {
            if (!first.getDepartureTime().toLocalDate().equals(departureDate)) {
                continue;
            }
            // Direct.
            if (first.getDestinationAirportCode().equals(destination)) {
                results.add(itinerary(List.of(first), List.of()));
                continue;
            }
            // 1 stop and 2 stops.
            for (Flight second : byOrigin.getOrDefault(first.getDestinationAirportCode(), List.of())) {
                long wait1 = layover.apply(first, second);
                if (wait1 < minLayover.apply(first, second) || wait1 > maxLayover) {
                    continue;
                }
                if (second.getDestinationAirportCode().equals(destination)) {
                    results.add(itinerary(List.of(first, second), List.of(wait1)));
                    continue;
                }
                if (second.getDestinationAirportCode().equals(origin)) {
                    continue;
                }
                for (Flight third : byOrigin.getOrDefault(second.getDestinationAirportCode(), List.of())) {
                    if (!third.getDestinationAirportCode().equals(destination)) {
                        continue;
                    }
                    long wait2 = layover.apply(second, third);
                    if (wait2 < minLayover.apply(second, third) || wait2 > maxLayover) {
                        continue;
                    }
                    results.add(itinerary(List.of(first, second, third), List.of(wait1, wait2)));
                }
            }
        }

        return results.stream()
                .filter(i -> i.totalDurationMinutes() <= maxTotal)
                .sorted(java.util.Comparator.comparingLong(
                        com.skybook.praveen.flightservice.dto.response.ItineraryResponse::totalDurationMinutes))
                .limit(20)
                .toList();
    }

    private com.skybook.praveen.flightservice.dto.response.ItineraryResponse itinerary(
            List<Flight> legs, List<Long> layovers) {
        Flight first = legs.get(0);
        Flight last = legs.get(legs.size() - 1);
        // Origin and final destination are usually different zones, so the
        // journey length has to be measured across them, not by subtracting
        // two wall clocks. Layovers are exempt: both sides of a connection are
        // the same airport, so those subtract correctly as they stand.
        long total = com.skybook.praveen.common.time.AirportTimeZones.elapsedBetween(
                first.getOriginAirportCode(), first.getDepartureTime(),
                last.getDestinationAirportCode(), last.getArrivalTime()).toMinutes();
        return new com.skybook.praveen.flightservice.dto.response.ItineraryResponse(
                legs.stream().map(FlightMapper::toResponse).toList(),
                legs.size() - 1,
                total,
                layovers,
                legs.stream().map(Flight::getAirlineCode).distinct().count() == 1);
    }

    @Override
    public List<RouteCalendarDayResponse> getRouteCalendar(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate startDate,
            LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        // Three visible months plus paging slack; the cap keeps a single public,
        // tokenless call from walking the whole year of schedule.
        if (startDate.plusDays(124).isBefore(endDate)) {
            throw new IllegalArgumentException("Calendar range must not exceed 124 days");
        }

        // Same bookability cutoff as itineraries: the calendar must not price
        // a "today" whose remaining departures have already left.
        LocalDateTime bookableFrom = showPastFlights
                ? LocalDateTime.MIN
                : LocalDateTime.now().plusMinutes(BOOKING_CUTOFF_MINUTES);

        String origin = originAirportCode.toUpperCase();
        String destination = destinationAirportCode.toUpperCase();

        // The calendar counts JOURNEY OPTIONS, not direct flights. The honest
        // networks (India, UK) serve real route maps, so most city pairs are
        // one-stop journeys via a hub - exactly what /itineraries composes.
        // Counting only nonstops painted every day grey for those pairs while
        // a search on the same day succeeded: the picker was contradicting
        // the search it exists to feed. Direct legs and hub connections are
        // counted with the itinerary composer's own layover rules, so a lit
        // day is precisely a day a search will satisfy (to one stop; the
        // rare pair reachable only with two stops still searches fine).
        List<Flight> outbound = flightRepository
                .findByOriginAirportCodeAndDepartureTimeBetween(
                        origin,
                        startDate.atStartOfDay(),
                        endDate.plusDays(1).atStartOfDay().minusNanos(1))
                .stream()
                .filter(flight -> flight.getStatus() != FlightStatus.CANCELLED)
                .filter(flight -> flight.getDepartureTime().isAfter(bookableFrom))
                .toList();

        // Possible onward legs: everything INTO the destination departing in
        // the window plus a day - an overnight first leg hands over to an
        // onward that leaves the following morning. A leg departing the
        // origin itself is excluded: that is the direct flight, not a hub.
        java.util.Map<String, List<Flight>> onwardByHub = flightRepository
                .findByDestinationAirportCodeAndDepartureTimeBetween(
                        destination,
                        startDate.atStartOfDay(),
                        endDate.plusDays(2).atStartOfDay())
                .stream()
                .filter(flight -> flight.getStatus() != FlightStatus.CANCELLED)
                .filter(flight -> flight.getDepartureTime().isAfter(bookableFrom))
                .filter(flight -> !flight.getOriginAirportCode().equals(origin))
                .sorted(java.util.Comparator.comparing(Flight::getDepartureTime))
                .collect(java.util.stream.Collectors.groupingBy(Flight::getOriginAirportCode));

        java.util.TreeMap<LocalDate, Integer> options = new java.util.TreeMap<>();
        for (Flight first : outbound) {
            LocalDate day = first.getDepartureTime().toLocalDate();
            if (first.getDestinationAirportCode().equals(destination)) {
                options.merge(day, 1, Integer::sum);
                continue;
            }
            List<Flight> onward = onwardByHub.get(first.getDestinationAirportCode());
            if (onward == null) {
                continue;
            }
            int connections = 0;
            for (Flight second : onward) {
                long wait = java.time.Duration
                        .between(first.getArrivalTime(), second.getDepartureTime()).toMinutes();
                if (wait > MAX_LAYOVER_MINUTES) {
                    break; // sorted by departure - every later leg waits longer
                }
                long minWait = first.getAirlineCode().equals(second.getAirlineCode())
                        ? MIN_LAYOVER_SAME_CARRIER
                        : MIN_LAYOVER_SELF_TRANSFER;
                if (wait >= minWait) {
                    connections++;
                }
            }
            if (connections > 0) {
                options.merge(day, connections, Integer::sum);
            }
        }

        return options.entrySet()
                .stream()
                .map(entry -> new RouteCalendarDayResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public List<FlightResponse> getFlightsByStatus(FlightStatus status) {

        return flightRepository.findByStatus(status)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public List<FlightResponse> getFlightsByDepartureDate(
            LocalDate departureDate) {

        LocalDateTime start = departureDate.atStartOfDay();
        LocalDateTime end = departureDate.plusDays(1)
                .atStartOfDay()
                .minusNanos(1);

        return flightRepository.findByDepartureTimeBetween(start, end)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public List<FlightResponse> getFlightsByDepartureDateRange(
            LocalDate startDate,
            LocalDate endDate) {

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1)
                .atStartOfDay()
                .minusNanos(1);

        return flightRepository.findByDepartureTimeBetween(start, end)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public FlightResponse updateFlightStatus(
            Long id,
            FlightStatus status) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(status);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse cancelFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.CANCELLED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse delayFlight(
            Long id,
            LocalDateTime newDepartureTime,
            LocalDateTime newArrivalTime) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        if (!newArrivalTime.isAfter(newDepartureTime)) {
            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        flight.setDepartureTime(newDepartureTime);
        flight.setArrivalTime(newArrivalTime);
        flight.setStatus(FlightStatus.DELAYED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse rescheduleFlight(
            Long id,
            LocalDateTime departureTime,
            LocalDateTime arrivalTime) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        if (!arrivalTime.isAfter(departureTime)) {
            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        flight.setDepartureTime(departureTime);
        flight.setArrivalTime(arrivalTime);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse boardFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.BOARDING);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse departFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.DEPARTED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse arriveFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.ARRIVED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public boolean exists(Long id) {
        return flightRepository.existsById(id);
    }

    @Override
    public boolean existsByFlightNumber(String flightNumber) {
        return flightRepository.existsByFlightNumber(
                flightNumber.toUpperCase());
    }
}
