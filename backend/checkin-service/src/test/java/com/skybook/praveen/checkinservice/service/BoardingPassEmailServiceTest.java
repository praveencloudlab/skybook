package com.skybook.praveen.checkinservice.service;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.entity.BoardingPassEmailLog;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.producer.CheckInEventProducer;
import com.skybook.praveen.checkinservice.repository.BoardingPassEmailLogRepository;
import com.skybook.praveen.security.AuthenticatedPrincipal;
import com.skybook.praveen.security.TokenType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.skybook.praveen.checkinservice.exception.BoardingPassEmailException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The boarding-pass email re-send (GUEST_CHECKIN_MODULE.md §5): checked-in
 * only, throttled through shared state, every delivery audited and
 * attributable - and the address itself never stored, only hashed.
 */
@ExtendWith(MockitoExtension.class)
class BoardingPassEmailServiceTest {

    @Mock
    private CheckInService checkInService;
    @Mock
    private BoardingPassService boardingPassService;
    @Mock
    private BoardingPassEmailLogRepository emailLogRepository;
    @Mock
    private CheckInEventProducer eventProducer;

    @InjectMocks
    private BoardingPassEmailService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAsGuestOf(long bookingId) {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "guest:" + bookingId, TokenType.GUEST, List.of("ROLE_GUEST"), "skybook-api", bookingId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
    }

    private CheckInResponse checkInWithStatus(CheckInStatus status) {
        CheckInResponse checkIn = mock(CheckInResponse.class);
        when(checkIn.status()).thenReturn(status);
        return checkIn;
    }

    @Test
    void refusesToEmailBeforeCheckIn() {
        CheckInResponse open = checkInWithStatus(CheckInStatus.OPEN);
        when(checkInService.getById(7L)).thenReturn(open);

        assertThatThrownBy(() -> service.emailBoardingPass(7L, "me@example.com"))
                .isInstanceOf(BoardingPassEmailException.class)
                .satisfies(e -> assertThat(((BoardingPassEmailException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(emailLogRepository, eventProducer);
    }

    @Test
    void throttlesTheFourthSendInsideTheWindow() {
        CheckInResponse checkedIn = checkInWithStatus(CheckInStatus.CHECKED_IN);
        when(checkInService.getById(7L)).thenReturn(checkedIn);
        when(emailLogRepository.countByCheckInIdAndSentAtAfter(eq(7L), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.emailBoardingPass(7L, "me@example.com"))
                .isInstanceOf(BoardingPassEmailException.class)
                .satisfies(e -> assertThat(((BoardingPassEmailException) e).status())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verifyNoInteractions(eventProducer);
    }

    @Test
    void sendsAuditsAndAttributesTheDelivery() {
        signedInAsGuestOf(41L);
        CheckInResponse checkIn = checkInWithStatus(CheckInStatus.CHECKED_IN);
        BoardingPassResponse pass = mock(BoardingPassResponse.class);
        when(checkInService.getById(7L)).thenReturn(checkIn);
        when(emailLogRepository.countByCheckInIdAndSentAtAfter(anyLong(), any())).thenReturn(0L);
        when(boardingPassService.getActiveForCheckIn(7L)).thenReturn(pass);

        service.emailBoardingPass(7L, "  Second.Inbox@Example.com ");

        ArgumentCaptor<BoardingPassEmailLog> logged = ArgumentCaptor.forClass(BoardingPassEmailLog.class);
        verify(emailLogRepository).save(logged.capture());
        assertThat(logged.getValue().getCheckInId()).isEqualTo(7L);
        assertThat(logged.getValue().getRequestedBy()).isEqualTo("guest:41");
        // The audit proves volume + attribution WITHOUT becoming a mailing
        // list: a 64-hex SHA-256, never the address.
        assertThat(logged.getValue().getAddressHash()).hasSize(64).doesNotContain("@");

        ArgumentCaptor<String> emailUsed = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resendId = ArgumentCaptor.forClass(String.class);
        verify(eventProducer).publishBoardingPassEmailRequested(
                eq(checkIn), eq(pass), emailUsed.capture(), resendId.capture(), eq("guest:41"));
        assertThat(emailUsed.getValue()).isEqualTo("Second.Inbox@Example.com");
        assertThat(resendId.getValue()).isEqualTo(logged.getValue().getResendId());
    }
}
