package com.skybook.praveen.inventoryservice.scheduler;

import com.skybook.praveen.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep logic itself (which holds expire, count restoration, history)
 * lives in InventoryService.expireHolds() and is covered by
 * InventoryServiceImplTest - this only verifies the job delegates and stays
 * quiet when there is nothing to do.
 */
@ExtendWith(MockitoExtension.class)
class SeatHoldExpiryJobTest {

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private SeatHoldExpiryJob job;

    @Test
    void delegatesTheSweepToTheService() {
        when(inventoryService.expireHolds()).thenReturn(2);

        job.expireHolds();

        verify(inventoryService).expireHolds();
    }

    @Test
    void zeroExpiredHoldsIsANoOpWithoutErrors() {
        when(inventoryService.expireHolds()).thenReturn(0);

        assertThatCode(() -> job.expireHolds()).doesNotThrowAnyException();
    }
}
