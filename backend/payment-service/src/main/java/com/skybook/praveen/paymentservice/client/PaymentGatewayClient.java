package com.skybook.praveen.paymentservice.client;

import java.math.BigDecimal;

/**
 * THE seam of this module (design doc section 5). Everything above this
 * interface is gateway-agnostic: plugging in Stripe/Adyen/Worldpay later
 * means one new implementation class plus configuration - nothing else
 * changes.
 *
 * Implementations must never be called inside a database transaction
 * (external I/O - see the facade flow in design doc section 2).
 */
public interface PaymentGatewayClient {

    GatewayResult authorize(String paymentReference, BigDecimal amount, String currency);

    GatewayResult capture(String gatewayReference, BigDecimal amount);

    GatewayResult voidAuthorization(String gatewayReference);

    GatewayResult refund(String gatewayReference, BigDecimal amount);
}
