package com.skybook.praveen.paymentservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic simulated gateway (design doc section 5) so every failure
 * path is reproducible by hand and testable without mocks:
 *
 * - amounts ending in .13 -> authorization declined  (SIM_DECLINED)
 * - amounts ending in .31 -> capture fails           (SIM_CAPTURE_FAILED)
 * - everything else succeeds
 *
 * Captures/voids/refunds carry no amount-based failure except the .31
 * capture rule; refund and void always succeed (simulated money always
 * flows back).
 */
@Slf4j
@Component
public class SimulatedPaymentGateway implements PaymentGatewayClient {

    private static final String DECLINE_SUFFIX = "0.13";
    private static final String CAPTURE_FAIL_SUFFIX = "0.31";

    @Override
    public GatewayResult authorize(String paymentReference, BigDecimal amount, String currency) {
        long start = System.nanoTime();
        if (endsWith(amount, DECLINE_SUFFIX)) {
            log.info("SIMULATED gateway DECLINED authorization for {} ({} {})", paymentReference, amount, currency);
            return GatewayResult.simulated(false, null, "SIM_DECLINED",
                    "Card declined by simulated gateway", amount, elapsedMs(start));
        }
        String reference = "SIM-" + UUID.randomUUID();
        log.info("SIMULATED gateway authorized {} ({} {}) -> {}", paymentReference, amount, currency, reference);
        return GatewayResult.simulated(true, reference, "SIM_OK",
                "Authorized", amount, elapsedMs(start));
    }

    @Override
    public GatewayResult capture(String gatewayReference, BigDecimal amount) {
        long start = System.nanoTime();
        if (endsWith(amount, CAPTURE_FAIL_SUFFIX)) {
            log.info("SIMULATED gateway FAILED capture on {} ({})", gatewayReference, amount);
            return GatewayResult.simulated(false, gatewayReference, "SIM_CAPTURE_FAILED",
                    "Capture failed at simulated gateway", amount, elapsedMs(start));
        }
        return GatewayResult.simulated(true, gatewayReference, "SIM_OK",
                "Captured", amount, elapsedMs(start));
    }

    @Override
    public GatewayResult voidAuthorization(String gatewayReference) {
        long start = System.nanoTime();
        return GatewayResult.simulated(true, gatewayReference, "SIM_OK",
                "Authorization voided", BigDecimal.ZERO, elapsedMs(start));
    }

    @Override
    public GatewayResult refund(String gatewayReference, BigDecimal amount) {
        long start = System.nanoTime();
        return GatewayResult.simulated(true, gatewayReference, "SIM_OK",
                "Refunded", amount, elapsedMs(start));
    }

    private static boolean endsWith(BigDecimal amount, String suffix) {
        return amount != null && amount.toPlainString().endsWith(suffix.substring(1));
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
