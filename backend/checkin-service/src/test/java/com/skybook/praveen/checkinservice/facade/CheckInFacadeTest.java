package com.skybook.praveen.checkinservice.facade;

import com.skybook.praveen.checkinservice.client.FlightCheckInDetails;
import com.skybook.praveen.checkinservice.client.FlightCheckInStatus;
import com.skybook.praveen.checkinservice.client.FlightServiceClient;
import com.skybook.praveen.checkinservice.client.InventoryServiceClient;
import com.skybook.praveen.checkinservice.client.SeatReservationDetails;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.SeatUnavailableException;
import com.skybook.praveen.checkinservice.producer.CheckInEventProducer;
import com.skybook.praveen.checkinservice.service.BoardingPassService;
import com.skybook.praveen.checkinservice.service.CheckInService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckInFacadeTest {

    @Mock
    private CheckInService checkInService;
    @Mock
    private BoardingPassService boardingPassService;
    @Mock
    private FlightServiceClient flightServiceClient;
    @Mock
    private InventoryServiceClient inventoryServiceClient;
    @Mock
    private CheckInEventProducer producer;

    private CheckInFacade facade;

    @BeforeEach
    void setUp() {
        facade = new CheckInFacade(
                checkInService, boardingPassService, flightServiceClient, inventoryServiceClient, producer, 20);
    }

    private CheckInResponse response(CheckInStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new CheckInResponse(1L, 42L, "SBTEST", 100L, 7L, "BA178", "LHR", "JFK", now.plusHours(2),
                "Test Passenger", "12B", "ECONOMY", "FLEXI", status, true, null, null, null, null, 0L, now, now);
    }

    private FlightCheckInDetails flight(FlightCheckInStatus status) {
        return new FlightCheckInDetails(7L, "BA178", "LHR", "JFK", LocalDateTime.now().plusHours(2), null, status);
    }

    private BoardingPassResponse boardingPass(String number) {
        LocalDateTime now = LocalDateTime.now();
        return new BoardingPassResponse(20L, 1L, number, "token", "Test Passenger", "SBTEST",
                "BA178", "LHR", "JFK", "12B", null, null, "3", BoardingPassStatus.ACTIVE, now, null, null);
    }

    @Test
    void checkInRejectsWhenFlightIsCancelled() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.OPEN));
        when(flightServiceClient.getFlight(7L)).thenReturn(flight(FlightCheckInStatus.CANCELLED));

        assertThatThrownBy(() -> facade.checkIn(1L)).isInstanceOf(IllegalStateException.class);

        verify(checkInService, never()).recordCheckIn(any());
    }

    @Test
    void checkInRejectsWhenSeatIsNotInTheReservationList() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.OPEN));
        when(flightServiceClient.getFlight(7L)).thenReturn(flight(FlightCheckInStatus.SCHEDULED));
        when(inventoryServiceClient.getReservationsForBooking(42L)).thenReturn(
                List.of(new SeatReservationDetails(1L, "14C", "RESERVED"))); // different seat

        assertThatThrownBy(() -> facade.checkIn(1L)).isInstanceOf(SeatUnavailableException.class);

        verify(checkInService, never()).recordCheckIn(any());
    }

    @Test
    void checkInProceedsWhenNoReservationsAreTrackedAtAll() {
        // Empty list is treated permissively - no inventory tracked for this
        // flight, or nothing to check against either way.
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.OPEN));
        when(flightServiceClient.getFlight(7L)).thenReturn(flight(FlightCheckInStatus.SCHEDULED));
        when(inventoryServiceClient.getReservationsForBooking(42L)).thenReturn(List.of());
        CheckInResponse checkedIn = response(CheckInStatus.CHECKED_IN);
        when(checkInService.recordCheckIn(1L)).thenReturn(checkedIn);
        BoardingPassResponse pass = boardingPass("BP-2026-AAAAAA");
        when(boardingPassService.generate(1L)).thenReturn(pass);

        CheckInResponse result = facade.checkIn(1L);

        assertThat(result.status()).isEqualTo(CheckInStatus.CHECKED_IN);
        verify(producer).publishPassengerCheckedIn(checkedIn);
        verify(producer).publishBoardingPassGenerated(checkedIn, pass);
    }

    @Test
    void checkInSucceedsWhenSeatIsCorrectlyReserved() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.OPEN));
        when(flightServiceClient.getFlight(7L)).thenReturn(flight(FlightCheckInStatus.SCHEDULED));
        when(inventoryServiceClient.getReservationsForBooking(42L)).thenReturn(
                List.of(new SeatReservationDetails(1L, "12B", "RESERVED")));
        CheckInResponse checkedIn = response(CheckInStatus.CHECKED_IN);
        when(checkInService.recordCheckIn(1L)).thenReturn(checkedIn);
        when(boardingPassService.generate(1L)).thenReturn(boardingPass("BP-2026-AAAAAA"));

        facade.checkIn(1L);

        verify(checkInService).recordCheckIn(1L);
    }

    @Test
    void boardRejectsWhenFlightIsCancelled() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.CHECKED_IN));
        when(flightServiceClient.getFlight(7L)).thenReturn(flight(FlightCheckInStatus.CANCELLED));

        assertThatThrownBy(() -> facade.board(1L)).isInstanceOf(IllegalStateException.class);

        verify(checkInService, never()).recordBoarding(any());
    }

    @Test
    void boardPublishesPassengerBoarded() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.CHECKED_IN));
        when(flightServiceClient.getFlight(7L)).thenReturn(flight(FlightCheckInStatus.SCHEDULED));
        CheckInResponse boarded = response(CheckInStatus.BOARDED);
        when(checkInService.recordBoarding(1L)).thenReturn(boarded);

        facade.board(1L);

        verify(producer).publishPassengerBoarded(boarded);
    }

    @Test
    void changeSeatReservesNewSeatBeforeTouchingTheDatabase() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.CHECKED_IN)); // seat 12B
        when(inventoryServiceClient.reserveSeat(7L, "14C", 42L, 100L)).thenReturn(Optional.empty());
        CheckInResponse updated = response(CheckInStatus.CHECKED_IN);
        when(checkInService.changeSeatNumber(1L, "14C")).thenReturn(updated);
        when(boardingPassService.reissueForSeatChange(1L)).thenReturn(Optional.empty());

        facade.changeSeat(1L, "14C");

        verify(inventoryServiceClient).reserveSeat(7L, "14C", 42L, 100L);
        verify(checkInService).changeSeatNumber(1L, "14C");
        verify(inventoryServiceClient).cancelReservationQuietly(7L, "12B", 42L, "Seat changed to 14C");
    }

    @Test
    void changeSeatDoesNotTouchTheDatabaseWhenTheNewSeatIsUnavailable() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.CHECKED_IN));
        when(inventoryServiceClient.reserveSeat(7L, "14C", 42L, 100L))
                .thenThrow(new SeatUnavailableException(7L, "14C", "already reserved"));

        assertThatThrownBy(() -> facade.changeSeat(1L, "14C")).isInstanceOf(SeatUnavailableException.class);

        verify(checkInService, never()).changeSeatNumber(any(), any());
        verify(inventoryServiceClient, never()).cancelReservationQuietly(any(), any(), any(), any());
    }

    @Test
    void changeSeatReissuesTheBoardingPassWhenOneExists() {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.CHECKED_IN));
        when(inventoryServiceClient.reserveSeat(7L, "14C", 42L, 100L)).thenReturn(Optional.empty());
        CheckInResponse updated = response(CheckInStatus.CHECKED_IN);
        when(checkInService.changeSeatNumber(1L, "14C")).thenReturn(updated);
        BoardingPassResponse reissued = boardingPass("BP-2026-NEWONE");
        when(boardingPassService.reissueForSeatChange(1L)).thenReturn(Optional.of(reissued));

        facade.changeSeat(1L, "14C");

        verify(producer).publishBoardingPassGenerated(updated, reissued);
    }

    @Test
    void cancelForBookingRevokesAndPublishesForEachCancelledRow() {
        CheckInResponse cancelled = response(CheckInStatus.CANCELLED);
        when(checkInService.cancelAllForBooking(42L, "booking cancelled")).thenReturn(List.of(cancelled));

        facade.cancelForBooking(42L, "booking cancelled");

        verify(boardingPassService).revokeActive(1L, "booking cancelled");
        verify(producer).publishPassengerCheckInCancelled(cancelled);
    }

    @Test
    void cancelForBookingDoesNothingWhenNoRowsAreAffected() {
        when(checkInService.cancelAllForBooking(42L, "reason")).thenReturn(List.of());

        facade.cancelForBooking(42L, "reason");

        verifyNoInteractions(boardingPassService);
        verifyNoInteractions(producer);
    }

    @Test
    void sweepNoShowsRevokesAndPublishesForEachSweptRow() {
        CheckInResponse noShow = response(CheckInStatus.NO_SHOW);
        when(checkInService.sweepNoShows(any())).thenReturn(List.of(noShow));

        int swept = facade.sweepNoShows();

        assertThat(swept).isEqualTo(1);
        verify(boardingPassService).revokeActive(1L, "No-show");
        verify(producer).publishPassengerNoShow(noShow);
    }
}
