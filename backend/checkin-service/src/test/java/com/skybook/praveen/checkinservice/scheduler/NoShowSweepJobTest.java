package com.skybook.praveen.checkinservice.scheduler;

import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep logic itself (cutoff computation, transition, boarding-pass
 * revocation, event publishing) lives in CheckInFacade.sweepNoShows() and is
 * covered by CheckInFacadeTest - this only verifies the job delegates and
 * stays quiet when there is nothing to do, same shape as inventory-service's
 * SeatHoldExpiryJobTest.
 */
@ExtendWith(MockitoExtension.class)
class NoShowSweepJobTest {

    @Mock
    private CheckInFacade checkInFacade;

    @InjectMocks
    private NoShowSweepJob job;

    @Test
    void delegatesTheSweepToTheFacade() {
        when(checkInFacade.sweepNoShows()).thenReturn(2);

        job.sweepNoShows();

        verify(checkInFacade).sweepNoShows();
    }

    @Test
    void zeroSweptRowsIsANoOpWithoutErrors() {
        when(checkInFacade.sweepNoShows()).thenReturn(0);

        assertThatCode(() -> job.sweepNoShows()).doesNotThrowAnyException();
    }
}
