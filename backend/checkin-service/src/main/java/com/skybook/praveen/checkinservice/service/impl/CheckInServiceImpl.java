package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.CheckInStateMachine;
import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.CheckInNotFoundException;
import com.skybook.praveen.checkinservice.mapper.CheckInMapper;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import com.skybook.praveen.checkinservice.service.CheckInService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the CheckIn aggregate only (design doc section 3.1) - see
 * CheckInService's Javadoc for the pure-DB / no-I/O contract this
 * implementation honors. findCheckInOrThrow is package-visible so
 * BoardingPassServiceImpl/BaggageServiceImpl/ManifestServiceImpl can inject
 * this impl (not the interface) and reuse the lookup - same pattern
 * payment-service's RefundServiceImpl uses with PaymentServiceImpl.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckInServiceImpl implements CheckInService {

    private static final Set<CheckInStatus> SWEEPABLE = EnumSet.of(
            CheckInStatus.NOT_OPEN, CheckInStatus.OPEN, CheckInStatus.CHECKED_IN);

    private final CheckInRepository checkInRepository;
    private final CheckInStateMachine stateMachine;
    private final CheckInValidator validator;

    @Override
    @Transactional
    public CheckInResponse createCheckIn(CreateCheckInRequest request, String actor, String source,
                                          String correlationId) {

        var existing = checkInRepository.findByBookingPassengerId(request.bookingPassengerId());
        if (existing.isPresent()) {
            log.info("Duplicate check-in create for bookingPassengerId {} - check-in {} already exists",
                    request.bookingPassengerId(), existing.get().getId());
            return CheckInMapper.toResponse(existing.get());
        }

        CheckIn checkIn = CheckIn.builder()
                .status(CheckInStatus.NOT_OPEN)
                .bookingId(request.bookingId())
                .bookingReference(request.bookingReference())
                .bookingPassengerId(request.bookingPassengerId())
                .flightId(request.flightId())
                .flightNumber(request.flightNumber())
                .originAirportCode(request.originAirportCode())
                .destinationAirportCode(request.destinationAirportCode())
                .departureTime(request.departureTime())
                .passengerName(request.passengerName())
                .contactEmail(request.contactEmail())
                .seatNumber(request.seatNumber())
                .travelClass(request.travelClass())
                .fareType(request.fareType())
                .seatSurchargeEntitlement(request.seatSurchargeEntitlement())
                .entitlementCurrency(request.entitlementCurrency())
                .ownerSubject(request.ownerSubject())
                .documentVerified(request.documentVerified())
                .build();

        stateMachine.recordHistory(checkIn, CheckInHistoryType.CHECKIN_CREATED, actor, source, correlationId,
                "Check-in record created for passenger " + request.passengerName());

        CheckIn saved = checkInRepository.save(checkIn);
        log.info("Created check-in {} for booking {} passenger {}",
                saved.getId(), request.bookingReference(), request.passengerName());

        return CheckInMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckInResponse getById(Long id) {
        return CheckInMapper.toResponse(findCheckInOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckInResponse> getByBookingId(Long bookingId) {
        return checkInRepository.findByBookingId(bookingId).stream().map(CheckInMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckInResponse> getByFlightId(Long flightId) {
        return checkInRepository.findByFlightId(flightId).stream().map(CheckInMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public CheckInResponse openWindow(Long id) {

        CheckIn checkIn = findCheckInOrThrow(id);

        if (checkIn.getStatus() == CheckInStatus.OPEN) {
            return CheckInMapper.toResponse(checkIn);
        }

        stateMachine.transition(checkIn, CheckInStatus.OPEN, CheckInHistoryType.CHECKIN_OPENED,
                "USER", "API", null, "Check-in window opened");

        return CheckInMapper.toResponse(checkInRepository.save(checkIn));
    }

    @Override
    @Transactional
    public CheckInResponse recordCheckIn(Long id) {

        CheckIn checkIn = findCheckInOrThrow(id);

        // Implicit open, same stopgap pattern booking-service's checkInPassenger used
        // (design doc section 7) - no separate trigger opens the window yet in practice.
        if (checkIn.getStatus() == CheckInStatus.NOT_OPEN) {
            stateMachine.transition(checkIn, CheckInStatus.OPEN, CheckInHistoryType.CHECKIN_OPENED,
                    "USER", "API", null, "Check-in window opened implicitly");
        }

        validator.validateCheckInWindowOpen(checkIn, LocalDateTime.now());
        validator.validateDocumentVerified(checkIn);

        stateMachine.transition(checkIn, CheckInStatus.CHECKED_IN, CheckInHistoryType.CHECKED_IN,
                "USER", "API", null, "Passenger checked in");
        checkIn.setCheckedInAt(LocalDateTime.now());

        return CheckInMapper.toResponse(checkInRepository.save(checkIn));
    }

    @Override
    @Transactional
    public CheckInResponse recordBoarding(Long id) {

        CheckIn checkIn = findCheckInOrThrow(id);

        validator.validateBoardingWindow(checkIn, LocalDateTime.now());

        stateMachine.transition(checkIn, CheckInStatus.BOARDED, CheckInHistoryType.BOARDED,
                "USER", "API", null, "Passenger boarded");
        checkIn.setBoardedAt(LocalDateTime.now());

        return CheckInMapper.toResponse(checkInRepository.save(checkIn));
    }

    @Override
    @Transactional
    public CheckInResponse changeSeatNumber(Long id, String newSeatNumber) {

        CheckIn checkIn = findCheckInOrThrow(id);
        validator.validateSeatChangeAllowed(checkIn);

        String oldSeatNumber = checkIn.getSeatNumber();
        checkIn.setSeatNumber(newSeatNumber);

        stateMachine.recordHistory(checkIn, CheckInHistoryType.SEAT_CHANGED, "USER", "API", null,
                "Seat changed from " + oldSeatNumber + " to " + newSeatNumber);

        return CheckInMapper.toResponse(checkInRepository.save(checkIn));
    }

    @Override
    @Transactional
    public CheckInResponse assignGate(Long id, String gate) {

        CheckIn checkIn = findCheckInOrThrow(id);
        checkIn.setGate(gate);

        return CheckInMapper.toResponse(checkInRepository.save(checkIn));
    }

    @Override
    @Transactional
    public List<CheckInResponse> cancelAllForBooking(Long bookingId, String reason) {

        List<CheckIn> checkIns = checkInRepository.findByBookingId(bookingId);
        List<CheckIn> cancelled = checkIns.stream()
                .filter(checkIn -> stateMachine.canTransition(checkIn.getStatus(), CheckInStatus.CANCELLED))
                .toList();

        for (CheckIn checkIn : cancelled) {
            stateMachine.transition(checkIn, CheckInStatus.CANCELLED, CheckInHistoryType.CANCELLED,
                    "KAFKA", "BOOKING_EVENT", null, reason);
            checkInRepository.save(checkIn);
        }

        log.info("Cancelled {} check-in(s) for booking {} ({})", cancelled.size(), bookingId, reason);
        return cancelled.stream().map(CheckInMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public List<CheckInResponse> sweepNoShows(LocalDateTime departureCutoff) {

        List<CheckIn> overdue = checkInRepository.findByStatusInAndDepartureTimeBefore(SWEEPABLE, departureCutoff);

        for (CheckIn checkIn : overdue) {
            stateMachine.transition(checkIn, CheckInStatus.NO_SHOW, CheckInHistoryType.NO_SHOW,
                    "SYSTEM", "NO_SHOW_JOB", null, "Gate closed without boarding");
            checkInRepository.save(checkIn);
        }

        if (!overdue.isEmpty()) {
            log.info("No-show sweep marked {} check-in(s) as NO_SHOW", overdue.size());
        }

        return overdue.stream().map(CheckInMapper::toResponse).toList();
    }

    CheckIn findCheckInOrThrow(Long id) {
        return checkInRepository.findById(id).orElseThrow(() -> CheckInNotFoundException.byId(id));
    }
}
