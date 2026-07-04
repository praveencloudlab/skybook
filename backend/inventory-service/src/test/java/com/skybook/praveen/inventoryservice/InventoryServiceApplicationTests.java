package com.skybook.praveen.inventoryservice;

import com.skybook.praveen.inventoryservice.facade.InventoryFacade;
import com.skybook.praveen.inventoryservice.integration.AbstractPostgresSpringBootTest;
import com.skybook.praveen.inventoryservice.scheduler.SeatHoldExpiryJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load smoke test (the gap called out in
 * INVENTORY_SERVICE_MODULE.md section 17) - proves the full application
 * context assembles: JPA against real PostgreSQL, Kafka producer config,
 * Feign client proxy, security filter chain, scheduler.
 */
class InventoryServiceApplicationTests extends AbstractPostgresSpringBootTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void keyBeansAreWired() {
        assertThat(context.getBean(InventoryFacade.class)).isNotNull();
        assertThat(context.getBean(SeatHoldExpiryJob.class)).isNotNull();
    }
}
