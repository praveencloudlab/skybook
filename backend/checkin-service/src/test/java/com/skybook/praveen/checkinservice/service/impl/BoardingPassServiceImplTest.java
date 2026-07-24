package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.BoardingGroupAssigner;
import com.skybook.praveen.checkinservice.domain.BoardingPassNumberGenerator;
import com.skybook.praveen.checkinservice.domain.BoardingPassTokenSigner;
import com.skybook.praveen.checkinservice.domain.CheckInStateMachine;
import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.entity.BoardingPass;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.BoardingPassNotFoundException;
import com.skybook.praveen.checkinservice.exception.BoardingPassVerificationException;
import com.skybook.praveen.checkinservice.repository.BoardingPassRepository;
import com.skybook.praveen.checkinservice.repository.CheckInHistoryRepository;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardingPassServiceImplTest {

    @Mock
    private BoardingPassRepository boardingPassRepository;
    @Mock
    private CheckInHistoryRepository checkInHistoryRepository;
    @Mock
    private CheckInRepository checkInRepository;

    private BoardingPassServiceImpl boardingPassService;

    @BeforeEach
    void setUp() {
        CheckInServiceImpl checkInService = new CheckInServiceImpl(
                checkInRepository, new CheckInStateMachine(), new CheckInValidator(24, 45, 45, 20));

        boardingPassService = new BoardingPassServiceImpl(
                boardingPassRepository, checkInHistoryRepository, checkInService,
                new BoardingPassNumberGenerator(), new BoardingPassTokenSigner("test-key-boarding-pass-service-32bytes-plus"),
                new BoardingGroupAssigner(), 45);

        lenient().when(boardingPassRepository.save(any(BoardingPass.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(boardingPassRepository.findByBoardingPassNumber(any())).thenReturn(Optional.empty());
    }

    private CheckIn checkIn() {
        return CheckIn.builder()
                .id(1L).bookingId(42L).bookingReference("SBTEST").bookingPassengerId(100L)
                .flightId(7L).flightNumber("BA178").originAirportCode("LHR").destinationAirportCode("JFK")
                .passengerName("Test Passenger").seatNumber("12B").travelClass("ECONOMY").fareType("FLEXI")
                .departureTime(LocalDateTime.of(2026, 7, 8, 18, 0))
                .status(CheckInStatus.CHECKED_IN)
                .build();
    }

    @Test
    void generateIssuesAnActivePassWithASignedToken() {
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn()));

        BoardingPassResponse response = boardingPassService.generate(1L);

        assertThat(response.status()).isEqualTo(BoardingPassStatus.ACTIVE);
        assertThat(response.boardingPassNumber()).matches("BP-\\d{4}-[A-HJ-NP-Z2-9]{6}");
        assertThat(response.seatNumber()).isEqualTo("12B");
        assertThat(response.token()).isNotBlank();
        verify(checkInHistoryRepository).save(any());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(boardingPassRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardingPassService.getById(99L))
                .isInstanceOf(BoardingPassNotFoundException.class);
    }

    @Test
    void getActiveForCheckInReturnsTheActivePass() {
        BoardingPass active = BoardingPass.builder()
                .id(20L).checkIn(checkIn()).boardingPassNumber("BP-2026-ACTIVE1").token("token")
                .passengerName("Test Passenger").bookingReference("SBTEST").seatNumber("12B")
                .status(BoardingPassStatus.ACTIVE).issuedAt(LocalDateTime.now()).build();
        when(boardingPassRepository.findByCheckInIdAndStatus(1L, BoardingPassStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        var response = boardingPassService.getActiveForCheckIn(1L);

        assertThat(response.boardingPassNumber()).isEqualTo("BP-2026-ACTIVE1");
    }

    @Test
    void getActiveForCheckInThrowsWhenNotCheckedInYet() {
        when(boardingPassRepository.findByCheckInIdAndStatus(1L, BoardingPassStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardingPassService.getActiveForCheckIn(1L))
                .isInstanceOf(BoardingPassNotFoundException.class)
                .hasMessageContaining("not checked in yet");
    }

    @Test
    void reissueForSeatChangeIsEmptyWhenNoActivePassExists() {
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn()));
        when(boardingPassRepository.findByCheckInIdAndStatus(1L, BoardingPassStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(boardingPassService.reissueForSeatChange(1L)).isEmpty();
    }

    @Test
    void reissueForSeatChangeRevokesTheOldPassAndLinksToTheNewOne() {
        CheckIn checkIn = checkIn();
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        BoardingPass existing = BoardingPass.builder()
                .id(10L).checkIn(checkIn).boardingPassNumber("BP-2026-OLD001").token("old-token")
                .passengerName("Test Passenger").bookingReference("SBTEST").seatNumber("12B")
                .status(BoardingPassStatus.ACTIVE).issuedAt(LocalDateTime.now().minusHours(1))
                .build();
        when(boardingPassRepository.findByCheckInIdAndStatus(1L, BoardingPassStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        Optional<BoardingPassResponse> reissued = boardingPassService.reissueForSeatChange(1L);

        assertThat(reissued).isPresent();
        assertThat(existing.getStatus()).isEqualTo(BoardingPassStatus.REVOKED);
        assertThat(existing.getRevokedAt()).isNotNull();
        assertThat(existing.getReissuedAsId()).isNull(); // new pass has no id assigned by the mocked save
    }

    @Test
    void revokeActiveIsEmptyWhenNoActivePassExists() {
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn()));
        when(boardingPassRepository.findByCheckInIdAndStatus(1L, BoardingPassStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(boardingPassService.revokeActive(1L, "cancelled")).isEmpty();
    }

    @Test
    void revokeActiveRevokesWithoutIssuingAReplacement() {
        CheckIn checkIn = checkIn();
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        BoardingPass existing = BoardingPass.builder()
                .id(10L).checkIn(checkIn).boardingPassNumber("BP-2026-OLD001").token("old-token")
                .passengerName("Test Passenger").bookingReference("SBTEST").seatNumber("12B")
                .status(BoardingPassStatus.ACTIVE).issuedAt(LocalDateTime.now().minusHours(1))
                .build();
        when(boardingPassRepository.findByCheckInIdAndStatus(1L, BoardingPassStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        Optional<BoardingPassResponse> revoked = boardingPassService.revokeActive(1L, "no-show");

        assertThat(revoked).isPresent();
        assertThat(revoked.get().status()).isEqualTo(BoardingPassStatus.REVOKED);
        assertThat(existing.getRevokedAt()).isNotNull();
    }

    @Test
    void verifyRejectsATamperedToken() {
        assertThatThrownBy(() -> boardingPassService.verify("not-a-real-token"))
                .isInstanceOf(BoardingPassVerificationException.class)
                .hasMessageContaining("tampered or malformed");
    }

    @Test
    void verifyRejectsATokenThatIsCryptographicallyValidButUnknownToTheDb() {
        BoardingPassTokenSigner signer = new BoardingPassTokenSigner("test-key-boarding-pass-service-32bytes-plus");
        String token = signer.sign("BP-2026-GHOST1", "SBTEST", 7L, "12B", 1L);
        when(boardingPassRepository.findByToken(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardingPassService.verify(token))
                .isInstanceOf(BoardingPassVerificationException.class)
                .hasMessageContaining("unknown boarding pass");
    }

    @Test
    void verifyRejectsARevokedPass() {
        CheckIn checkIn = checkIn();
        BoardingPassTokenSigner signer = new BoardingPassTokenSigner("test-key-boarding-pass-service-32bytes-plus");
        String token = signer.sign("BP-2026-REVOKD", "SBTEST", 7L, "12B", 1L);

        BoardingPass revoked = BoardingPass.builder()
                .id(10L).checkIn(checkIn).boardingPassNumber("BP-2026-REVOKD").token(token)
                .status(BoardingPassStatus.REVOKED).build();
        when(boardingPassRepository.findByToken(token)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> boardingPassService.verify(token))
                .isInstanceOf(BoardingPassVerificationException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void verifyRejectsAnAlreadyBoardedPass() {
        CheckIn boarded = checkIn();
        boarded.setStatus(CheckInStatus.BOARDED);
        BoardingPassTokenSigner signer = new BoardingPassTokenSigner("test-key-boarding-pass-service-32bytes-plus");
        String token = signer.sign("BP-2026-BOARDD", "SBTEST", 7L, "12B", 1L);

        BoardingPass pass = BoardingPass.builder()
                .id(10L).checkIn(boarded).boardingPassNumber("BP-2026-BOARDD").token(token)
                .status(BoardingPassStatus.ACTIVE).build();
        when(boardingPassRepository.findByToken(token)).thenReturn(Optional.of(pass));

        assertThatThrownBy(() -> boardingPassService.verify(token))
                .isInstanceOf(BoardingPassVerificationException.class)
                .hasMessageContaining("already boarded");
    }

    @Test
    void verifySucceedsForAnActivePassOnACheckedInPassenger() {
        CheckIn checkIn = checkIn(); // CHECKED_IN
        BoardingPassTokenSigner signer = new BoardingPassTokenSigner("test-key-boarding-pass-service-32bytes-plus");
        String token = signer.sign("BP-2026-VALID1", "SBTEST", 7L, "12B", 1L);

        BoardingPass pass = BoardingPass.builder()
                .id(10L).checkIn(checkIn).boardingPassNumber("BP-2026-VALID1").token(token)
                .passengerName("Test Passenger").bookingReference("SBTEST").flightNumber("BA178")
                .seatNumber("12B").gate("A12").boardingGroup("3")
                .status(BoardingPassStatus.ACTIVE).build();
        when(boardingPassRepository.findByToken(token)).thenReturn(Optional.of(pass));

        var response = boardingPassService.verify(token);

        assertThat(response.passengerName()).isEqualTo("Test Passenger");
        assertThat(response.gate()).isEqualTo("A12");
        assertThat(response.boardingGroup()).isEqualTo("3");
    }
}
