package com.skybook.praveen.bookingservice.service;

import com.skybook.praveen.bookingservice.entity.Booking;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.GuestLookupAttempt;
import com.skybook.praveen.bookingservice.entity.Passenger;
import com.skybook.praveen.bookingservice.repository.BookingRepository;
import com.skybook.praveen.bookingservice.repository.GuestLookupAttemptRepository;
import com.skybook.praveen.bookingservice.security.GuestTokenFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guest-session issuance (GUEST_CHECKIN_MODULE.md §3/§6). The properties that
 * carry weight are the negative ones: every mismatch is ONE indistinguishable
 * 404, the throttle fires before verification, and no failure path ever
 * reaches the token mint.
 */
@ExtendWith(MockitoExtension.class)
class GuestSessionServiceTest {

    private static final String REF = "SKY41X";

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private GuestLookupAttemptRepository attemptRepository;
    @Mock
    private GuestTokenFetcher guestTokenFetcher;

    @InjectMocks
    private GuestSessionService service;

    private static Booking booking(String status, String... activeLastNames) {
        Booking booking = new Booking();
        booking.setId(41L);
        booking.setBookingStatus(com.skybook.praveen.bookingservice.enums.BookingStatus.valueOf(status));
        for (String lastName : activeLastNames) {
            booking.getPassengers().add(passengerRow(lastName, false));
        }
        return booking;
    }

    private static BookingPassenger passengerRow(String lastName, boolean cancelled) {
        Passenger person = new Passenger();
        person.setLastName(lastName);
        BookingPassenger row = new BookingPassenger();
        row.setPassenger(person);
        row.setCancelled(cancelled);
        return row;
    }

    @Test
    void issuesABookingScopedSessionForAMatchingActivePassenger() {
        when(attemptRepository.countByBookingReferenceAndAttemptedAtAfter(eq(REF), any())).thenReturn(0L);
        when(bookingRepository.findByBookingReference(REF)).thenReturn(Optional.of(booking("CONFIRMED", "Varma")));
        when(guestTokenFetcher.fetch(41L)).thenReturn("guest-jwt");

        GuestSessionService.GuestSession session = service.issue("  sky41x ", "VARMA");

        assertThat(session.bookingId()).isEqualTo(41L);
        assertThat(session.token()).isEqualTo("guest-jwt");
        // A success is not a failure: nothing is recorded against the brake.
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void everyMismatchArmAnswersTheSameGeneric404() {
        when(attemptRepository.countByBookingReferenceAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        // unknown reference
        when(bookingRepository.findByBookingReference("GHOST1")).thenReturn(Optional.empty());
        // wrong surname
        when(bookingRepository.findByBookingReference("SKY41X"))
                .thenReturn(Optional.of(booking("CONFIRMED", "Varma")));
        // fully cancelled booking, right surname
        when(bookingRepository.findByBookingReference("SKY77Y"))
                .thenReturn(Optional.of(booking("CANCELLED", "Varma")));

        for (String[] attempt : new String[][]{
                {"GHOST1", "Varma"}, {"SKY41X", "Smith"}, {"SKY77Y", "Varma"}}) {
            assertThatThrownBy(() -> service.issue(attempt[0], attempt[1]))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> {
                        ResponseStatusException rse = (ResponseStatusException) e;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(rse.getReason()).isEqualTo(GuestSessionService.GENERIC_MISMATCH);
                    });
        }
        verifyNoInteractions(guestTokenFetcher);
    }

    @Test
    void aCancelledPassengersSurnameUnlocksNothing() {
        // The §6 privacy rule: removed-from-the-booking means removed from
        // the lock, even though the row still exists for refund arithmetic.
        when(attemptRepository.countByBookingReferenceAndAttemptedAtAfter(eq(REF), any())).thenReturn(0L);
        Booking booking = booking("CONFIRMED", "Varma");
        booking.getPassengers().add(passengerRow("Removed", true));
        when(bookingRepository.findByBookingReference(REF)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.issue(REF, "Removed"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void failuresAreRecordedAgainstTheReferenceBrake() {
        when(attemptRepository.countByBookingReferenceAndAttemptedAtAfter(eq("GHOST1"), any())).thenReturn(0L);
        when(bookingRepository.findByBookingReference("GHOST1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue("ghost1", "Anyone"))
                .isInstanceOf(ResponseStatusException.class);

        var captor = org.mockito.ArgumentCaptor.forClass(GuestLookupAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertThat(captor.getValue().getBookingReference()).isEqualTo("GHOST1");
        verify(attemptRepository).deleteOlderThan(any(Instant.class));
    }

    @Test
    void theBrakeFiresBeforeVerificationEvenForCorrectCredentials() {
        // Five failures lock the reference; attempt six is refused WITHOUT
        // touching the data - a distributed guess cannot brute the surname
        // while the window is hot, and a correct sixth guess learns nothing.
        when(attemptRepository.countByBookingReferenceAndAttemptedAtAfter(eq(REF), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.issue(REF, "Varma"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verifyNoInteractions(bookingRepository);
        verifyNoInteractions(guestTokenFetcher);
    }
}
