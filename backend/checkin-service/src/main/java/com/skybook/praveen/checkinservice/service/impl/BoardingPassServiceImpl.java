package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.BoardingGroupAssigner;
import com.skybook.praveen.checkinservice.domain.BoardingPassNumberGenerator;
import com.skybook.praveen.checkinservice.domain.BoardingPassTokenSigner;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassVerifyResponse;
import com.skybook.praveen.checkinservice.entity.BoardingPass;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.entity.CheckInHistory;
import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.BoardingPassNotFoundException;
import com.skybook.praveen.checkinservice.exception.BoardingPassVerificationException;
import com.skybook.praveen.checkinservice.mapper.BoardingPassMapper;
import com.skybook.praveen.checkinservice.repository.BoardingPassRepository;
import com.skybook.praveen.checkinservice.repository.CheckInHistoryRepository;
import com.skybook.praveen.checkinservice.service.BoardingPassService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * No @RequiredArgsConstructor - boardingOpensMinutesBeforeDeparture is a
 * @Value primitive, which needs an explicit constructor to be resolvable in
 * a plain unit test (field injection never populates outside a real Spring
 * context), same reasoning as CheckInValidator/BaggageAllowanceCalculator.
 */
@Slf4j
@Service
public class BoardingPassServiceImpl implements BoardingPassService {

    private static final int MAX_REFERENCE_ATTEMPTS = 10;

    private final BoardingPassRepository boardingPassRepository;
    private final CheckInHistoryRepository checkInHistoryRepository;

    // Injects the impl (not the interface) to reuse the aggregate lookup -
    // same pattern payment-service's RefundServiceImpl uses with
    // PaymentServiceImpl.
    private final CheckInServiceImpl checkInService;

    private final BoardingPassNumberGenerator numberGenerator;
    private final BoardingPassTokenSigner tokenSigner;
    private final BoardingGroupAssigner boardingGroupAssigner;
    private final long boardingOpensMinutesBeforeDeparture;

    public BoardingPassServiceImpl(BoardingPassRepository boardingPassRepository,
            CheckInHistoryRepository checkInHistoryRepository, CheckInServiceImpl checkInService,
            BoardingPassNumberGenerator numberGenerator, BoardingPassTokenSigner tokenSigner,
            BoardingGroupAssigner boardingGroupAssigner,
            @Value("${checkin.boarding.opens-minutes-before-departure:45}") long boardingOpensMinutesBeforeDeparture) {
        this.boardingPassRepository = boardingPassRepository;
        this.checkInHistoryRepository = checkInHistoryRepository;
        this.checkInService = checkInService;
        this.numberGenerator = numberGenerator;
        this.tokenSigner = tokenSigner;
        this.boardingGroupAssigner = boardingGroupAssigner;
        this.boardingOpensMinutesBeforeDeparture = boardingOpensMinutesBeforeDeparture;
    }

    @Override
    @Transactional
    public BoardingPassResponse generate(Long checkInId) {

        CheckIn checkIn = checkInService.findCheckInOrThrow(checkInId);

        String boardingPassNumber = uniqueBoardingPassNumber();
        String boardingGroup = boardingGroupAssigner.assign(checkIn.getTravelClass(), checkIn.getFareType());
        LocalDateTime boardingTime = checkIn.getDepartureTime() == null ? null
                : checkIn.getDepartureTime().minusMinutes(boardingOpensMinutesBeforeDeparture);
        String token = tokenSigner.sign(boardingPassNumber, checkIn.getBookingReference(),
                checkIn.getFlightId(), checkIn.getSeatNumber(), checkIn.getId());

        BoardingPass pass = BoardingPass.builder()
                .status(BoardingPassStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .checkIn(checkIn)
                .boardingPassNumber(boardingPassNumber)
                .token(token)
                .passengerName(checkIn.getPassengerName())
                .bookingReference(checkIn.getBookingReference())
                .flightNumber(checkIn.getFlightNumber())
                .originAirportCode(checkIn.getOriginAirportCode())
                .destinationAirportCode(checkIn.getDestinationAirportCode())
                .seatNumber(checkIn.getSeatNumber())
                .boardingTime(boardingTime)
                .boardingGroup(boardingGroup)
                .build();

        BoardingPass saved = boardingPassRepository.save(pass);
        recordHistory(checkIn, CheckInHistoryType.BOARDING_PASS_ISSUED,
                "Boarding pass " + boardingPassNumber + " issued");

        log.info("Issued boarding pass {} for check-in {}", boardingPassNumber, checkInId);
        return BoardingPassMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public Optional<BoardingPassResponse> reissueForSeatChange(Long checkInId) {

        CheckIn checkIn = checkInService.findCheckInOrThrow(checkInId);

        var currentActive = boardingPassRepository.findByCheckInIdAndStatus(checkInId, BoardingPassStatus.ACTIVE);
        if (currentActive.isEmpty()) {
            // No pass issued yet (seat changed before check-in) - nothing to reissue.
            return Optional.empty();
        }

        BoardingPass old = currentActive.get();
        BoardingPassResponse reissued = generate(checkInId);

        old.setStatus(BoardingPassStatus.REVOKED);
        old.setRevokedAt(LocalDateTime.now());
        old.setReissuedAsId(reissued.id());
        boardingPassRepository.save(old);

        recordHistory(checkIn, CheckInHistoryType.BOARDING_PASS_REVOKED,
                "Boarding pass " + old.getBoardingPassNumber() + " revoked - reissued as " + reissued.boardingPassNumber());

        log.info("Reissued boarding pass for check-in {}: {} -> {}",
                checkInId, old.getBoardingPassNumber(), reissued.boardingPassNumber());
        return Optional.of(reissued);
    }

    @Override
    @Transactional
    public Optional<BoardingPassResponse> revokeActive(Long checkInId, String reason) {

        CheckIn checkIn = checkInService.findCheckInOrThrow(checkInId);

        var currentActive = boardingPassRepository.findByCheckInIdAndStatus(checkInId, BoardingPassStatus.ACTIVE);
        if (currentActive.isEmpty()) {
            return Optional.empty();
        }

        BoardingPass pass = currentActive.get();
        pass.setStatus(BoardingPassStatus.REVOKED);
        pass.setRevokedAt(LocalDateTime.now());
        BoardingPass saved = boardingPassRepository.save(pass);

        recordHistory(checkIn, CheckInHistoryType.BOARDING_PASS_REVOKED,
                "Boarding pass " + pass.getBoardingPassNumber() + " revoked (" + reason + ")");

        log.info("Revoked boarding pass {} for check-in {} ({})", pass.getBoardingPassNumber(), checkInId, reason);
        return Optional.of(BoardingPassMapper.toResponse(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public BoardingPassResponse getById(Long id) {
        return BoardingPassMapper.toResponse(boardingPassRepository.findById(id)
                .orElseThrow(() -> BoardingPassNotFoundException.byId(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public BoardingPassResponse getActiveForCheckIn(Long checkInId) {
        return BoardingPassMapper.toResponse(
                boardingPassRepository.findByCheckInIdAndStatus(checkInId, BoardingPassStatus.ACTIVE)
                        .orElseThrow(() -> BoardingPassNotFoundException.byCheckIn(checkInId)));
    }

    @Override
    @Transactional(readOnly = true)
    public BoardingPassVerifyResponse verify(String token) {

        var payload = tokenSigner.verify(token);
        if (payload.isEmpty()) {
            throw new BoardingPassVerificationException("tampered or malformed token");
        }

        BoardingPass pass = boardingPassRepository.findByToken(token)
                .orElseThrow(() -> new BoardingPassVerificationException("unknown boarding pass"));

        if (pass.getStatus() != BoardingPassStatus.ACTIVE) {
            throw new BoardingPassVerificationException("revoked");
        }

        CheckInStatus checkInStatus = pass.getCheckIn().getStatus();
        if (checkInStatus == CheckInStatus.BOARDED || checkInStatus == CheckInStatus.COMPLETED) {
            throw new BoardingPassVerificationException("already boarded");
        }
        if (checkInStatus != CheckInStatus.CHECKED_IN) {
            throw new BoardingPassVerificationException("check-in is " + checkInStatus + " - not valid for boarding");
        }

        return new BoardingPassVerifyResponse(
                pass.getPassengerName(),
                pass.getBookingReference(),
                pass.getFlightNumber(),
                pass.getSeatNumber(),
                pass.getGate(),
                pass.getBoardingGroup()
        );
    }

    private void recordHistory(CheckIn checkIn, CheckInHistoryType type, String details) {
        checkInHistoryRepository.save(CheckInHistory.builder()
                .checkIn(checkIn)
                .historyType(type)
                .actor("USER")
                .source("API")
                .details(details)
                .changedAt(LocalDateTime.now())
                .build());
    }

    private String uniqueBoardingPassNumber() {
        for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
            String candidate = numberGenerator.generate();
            if (boardingPassRepository.findByBoardingPassNumber(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique boarding pass number");
    }
}
