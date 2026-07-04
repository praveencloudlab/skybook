package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns seat-map requests into AircraftSeat entities attached to their
 * Aircraft, enforcing map-level rules that bean validation on the individual
 * rows cannot see (duplicates within the request, collisions with seats the
 * aircraft already has).
 */
@Component
public class SeatMapGenerator {

    /**
     * Builds and attaches seats to the aircraft (via the seats collection -
     * persisted by cascade) and updates the denormalized totalSeats.
     * Returns the newly created seats.
     */
    public List<AircraftSeat> generate(Aircraft aircraft, List<CreateAircraftSeatRequest> requests) {

        Set<String> seen = new HashSet<>();
        for (CreateAircraftSeatRequest request : requests) {
            if (!seen.add(request.seatNumber())) {
                throw new IllegalArgumentException("Duplicate seat number in request: " + request.seatNumber());
            }
        }

        Set<String> existing = new HashSet<>();
        for (AircraftSeat seat : aircraft.getSeats()) {
            existing.add(seat.getSeatNumber());
        }

        List<AircraftSeat> created = requests.stream()
                .map(request -> {
                    if (existing.contains(request.seatNumber())) {
                        throw new IllegalArgumentException(
                                "Seat " + request.seatNumber() + " already exists on aircraft "
                                        + aircraft.getRegistrationNumber());
                    }
                    return AircraftSeat.builder()
                            .aircraft(aircraft)
                            .seatNumber(request.seatNumber())
                            .rowNumber(request.rowNumber())
                            .seatType(request.seatType())
                            .position(request.position())
                            .exitRow(Boolean.TRUE.equals(request.exitRow()))
                            .build();
                })
                .toList();

        aircraft.getSeats().addAll(created);
        aircraft.setTotalSeats(aircraft.getSeats().size());

        return created;
    }
}
