package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.CheckInStateMachine;
import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.CheckInNotFoundException;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckInServiceImplTest {

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 7, 8, 18, 0);

    @Mock
    private CheckInRepository checkInRepository;

    private CheckInServiceImpl checkInService;

    @BeforeEach
    void setUp() {
        // Real domain collaborators - opens 24h before, closes 45m before,
        // boarding opens 45m before, gate closes 20m before.
        checkInService = new CheckInServiceImpl(
                checkInRepository, new CheckInStateMachine(), new CheckInValidator(24, 45, 45, 20));

        lenient().when(checkInRepository.save(any(CheckIn.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateCheckInRequest request() {
        return new CreateCheckInRequest(42L, "SBTEST", 100L, 7L, "BA178", "LHR", "JFK",
                DEPARTURE, "Test Passenger", "test@example.com", "12B", "ECONOMY", "FLEXI", true);
    }

    private CheckIn checkInWith(CheckInStatus status) {
        return CheckIn.builder()
                .id(1L).bookingId(42L).bookingReference("SBTEST").bookingPassengerId(100L)
                .flightId(7L).passengerName("Test Passenger").seatNumber("12B")
                .departureTime(DEPARTURE).status(status).documentVerified(true)
                .build();
    }

    @Test
    void createCheckInPersistsANewRow() {
        when(checkInRepository.findByBookingPassengerId(100L)).thenReturn(Optional.empty());

        CheckInResponse response = checkInService.createCheckIn(request(), "USER", "API", "SBTEST");

        assertThat(response.status()).isEqualTo(CheckInStatus.NOT_OPEN);
        assertThat(response.bookingPassengerId()).isEqualTo(100L);
        verify(checkInRepository).save(any(CheckIn.class));
    }

    @Test
    void createCheckInIsIdempotentByBookingPassengerId() {
        CheckIn existing = checkInWith(CheckInStatus.NOT_OPEN);
        when(checkInRepository.findByBookingPassengerId(100L)).thenReturn(Optional.of(existing));

        CheckInResponse response = checkInService.createCheckIn(request(), "KAFKA", "BOOKING_EVENT", "SBTEST");

        assertThat(response.id()).isEqualTo(1L);
        verify(checkInRepository, never()).save(any(CheckIn.class));
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(checkInRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkInService.getById(99L))
                .isInstanceOf(CheckInNotFoundException.class);
    }

    @Test
    void openWindowIsIdempotentWhenAlreadyOpen() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        checkInService.openWindow(1L);

        verify(checkInRepository, never()).save(any(CheckIn.class));
    }

    @Test
    void openWindowTransitionsFromNotOpen() {
        CheckIn checkIn = checkInWith(CheckInStatus.NOT_OPEN);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        CheckInResponse response = checkInService.openWindow(1L);

        assertThat(response.status()).isEqualTo(CheckInStatus.OPEN);
    }

    @Test
    void recordCheckInImplicitlyOpensThenChecksIn() {
        CheckIn checkIn = checkInWith(CheckInStatus.NOT_OPEN);
        // validateCheckInWindowOpen uses real LocalDateTime.now() internally -
        // anchor to now, not the fixed DEPARTURE constant.
        checkIn.setDepartureTime(LocalDateTime.now().plusHours(2));
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        CheckInResponse response = checkInService.recordCheckIn(1L);

        assertThat(response.status()).isEqualTo(CheckInStatus.CHECKED_IN);
        assertThat(response.checkedInAt()).isNotNull();
        // Both the implicit open and the check-in are recorded.
        assertThat(checkIn.getHistory()).hasSize(2);
    }

    @Test
    void recordCheckInBeforeWindowOpensThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.NOT_OPEN);
        checkIn.setDepartureTime(LocalDateTime.now().plusDays(3)); // window opens in ~2 days
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        assertThatThrownBy(() -> checkInService.recordCheckIn(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not open until");
    }

    @Test
    void recordCheckInWithoutDocumentVerificationThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        checkIn.setDepartureTime(LocalDateTime.now().plusHours(2));
        checkIn.setDocumentVerified(false);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        assertThatThrownBy(() -> checkInService.recordCheckIn(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("passport/document");
    }

    @Test
    void cannotCheckInTwice() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        assertThatThrownBy(() -> checkInService.recordCheckIn(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordBoardingBeforeCheckInThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        assertThatThrownBy(() -> checkInService.recordBoarding(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordBoardingFromCheckedInSucceeds() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        // Boarding window is real-clock-relative (validateBoardingWindow uses
        // LocalDateTime.now() internally) - anchor to now, not the fixed
        // DEPARTURE constant, so this doesn't depend on wall-clock time.
        checkIn.setDepartureTime(LocalDateTime.now().plusMinutes(30));
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        CheckInResponse response = checkInService.recordBoarding(1L);

        assertThat(response.status()).isEqualTo(CheckInStatus.BOARDED);
        assertThat(response.boardedAt()).isNotNull();
    }

    @Test
    void changeSeatNumberRejectedAfterBoarded() {
        CheckIn checkIn = checkInWith(CheckInStatus.BOARDED);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        assertThatThrownBy(() -> checkInService.changeSeatNumber(1L, "14C"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changeSeatNumberUpdatesTheSeatAndRecordsHistory() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        CheckInResponse response = checkInService.changeSeatNumber(1L, "14C");

        assertThat(response.seatNumber()).isEqualTo("14C");
        assertThat(checkIn.getHistory()).hasSize(1);
    }

    @Test
    void assignGateUpdatesTheField() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkIn));

        CheckInResponse response = checkInService.assignGate(1L, "A12");

        assertThat(response.gate()).isEqualTo("A12");
    }

    @Test
    void cancelAllForBookingOnlyCancelsNonTerminalRows() {
        CheckIn openRow = checkInWith(CheckInStatus.OPEN);
        CheckIn alreadyCompleted = checkInWith(CheckInStatus.COMPLETED);
        alreadyCompleted.setId(2L);
        when(checkInRepository.findByBookingId(42L)).thenReturn(List.of(openRow, alreadyCompleted));

        List<CheckInResponse> cancelled = checkInService.cancelAllForBooking(42L, "booking cancelled");

        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.getFirst().id()).isEqualTo(1L);
        assertThat(openRow.getStatus()).isEqualTo(CheckInStatus.CANCELLED);
        assertThat(alreadyCompleted.getStatus()).isEqualTo(CheckInStatus.COMPLETED);
    }

    @Test
    void sweepNoShowsOnlyAffectsRowsPastTheCutoff() {
        CheckIn overdue = checkInWith(CheckInStatus.OPEN);
        overdue.setDepartureTime(LocalDateTime.now().minusHours(1));

        LocalDateTime cutoff = LocalDateTime.now();
        when(checkInRepository.findByStatusInAndDepartureTimeBefore(any(), any()))
                .thenReturn(List.of(overdue));

        List<CheckInResponse> swept = checkInService.sweepNoShows(cutoff);

        assertThat(swept).hasSize(1);
        assertThat(overdue.getStatus()).isEqualTo(CheckInStatus.NO_SHOW);
    }
}
