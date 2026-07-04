package com.skybook.praveen.paymentservice.client;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void normalAmountsAuthorizeSuccessfully() {
        GatewayResult result = gateway.authorize("PAY-2026-TESTAA", new BigDecimal("100.00"), "USD");

        assertThat(result.success()).isTrue();
        assertThat(result.gatewayReference()).startsWith("SIM-");
        assertThat(result.responseCode()).isEqualTo("SIM_OK");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(1);
        assertThat(result.rawPayload()).contains("SIMULATED").contains("SIM_OK");
    }

    @Test
    void amountsEndingInPoint13AreDeclined() {
        // The documented magic amount (design doc section 5).
        GatewayResult result = gateway.authorize("PAY-2026-TESTAB", new BigDecimal("100.13"), "USD");

        assertThat(result.success()).isFalse();
        assertThat(result.responseCode()).isEqualTo("SIM_DECLINED");
        assertThat(result.gatewayReference()).isNull();
    }

    @Test
    void amountsEndingInPoint31FailCaptureButAuthorizeFine() {
        GatewayResult authorization = gateway.authorize("PAY-2026-TESTAC", new BigDecimal("100.31"), "USD");
        assertThat(authorization.success()).isTrue();

        GatewayResult capture = gateway.capture(authorization.gatewayReference(), new BigDecimal("100.31"));
        assertThat(capture.success()).isFalse();
        assertThat(capture.responseCode()).isEqualTo("SIM_CAPTURE_FAILED");
    }

    @Test
    void normalCaptureSucceedsAndKeepsTheGatewayReference() {
        GatewayResult capture = gateway.capture("SIM-existing-ref", new BigDecimal("100.00"));

        assertThat(capture.success()).isTrue();
        assertThat(capture.gatewayReference()).isEqualTo("SIM-existing-ref");
    }

    @Test
    void voidAndRefundAlwaysSucceed() {
        assertThat(gateway.voidAuthorization("SIM-ref").success()).isTrue();
        assertThat(gateway.refund("SIM-ref", new BigDecimal("70.00")).success()).isTrue();
        // Even magic amounts refund fine - only authorize/capture have failure rules.
        assertThat(gateway.refund("SIM-ref", new BigDecimal("70.13")).success()).isTrue();
    }
}
