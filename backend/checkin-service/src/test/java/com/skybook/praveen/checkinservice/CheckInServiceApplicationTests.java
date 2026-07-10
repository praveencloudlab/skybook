package com.skybook.praveen.checkinservice;

import com.skybook.praveen.checkinservice.integration.AbstractCheckInIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load smoke test on the existing Testcontainers base (real
 * PostgreSQL + Kafka), the same shape payment-service's
 * PaymentServiceApplicationTests already uses. Previously a plain
 * @SpringBootTest that inherited application.yml's localhost:5432
 * datasource - which only ever passed on a machine with a local Postgres
 * already running, and failed on the first CI run where no such service
 * exists. Extending the shared base also means this reuses the same Spring
 * context (and containers) as the other integration tests instead of
 * booting its own.
 */
class CheckInServiceApplicationTests extends AbstractCheckInIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

}
