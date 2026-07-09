package com.skybook.praveen.checkinservice.scheduler;

import com.skybook.praveen.checkinservice.service.ManifestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The finalization logic itself lives in
 * ManifestService.finalizeDueManifests() and is covered by
 * ManifestServiceImplTest - this only verifies the job delegates and stays
 * quiet when there is nothing to do, same shape as NoShowSweepJobTest.
 */
@ExtendWith(MockitoExtension.class)
class ManifestFinalizationJobTest {

    @Mock
    private ManifestService manifestService;

    @InjectMocks
    private ManifestFinalizationJob job;

    @Test
    void delegatesFinalizationToTheService() {
        when(manifestService.finalizeDueManifests()).thenReturn(3);

        job.finalizeDueManifests();

        verify(manifestService).finalizeDueManifests();
    }

    @Test
    void zeroFinalizedManifestsIsANoOpWithoutErrors() {
        when(manifestService.finalizeDueManifests()).thenReturn(0);

        assertThatCode(() -> job.finalizeDueManifests()).doesNotThrowAnyException();
    }
}
