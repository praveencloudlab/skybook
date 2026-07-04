package com.skybook.praveen.paymentservice.integration;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import com.skybook.praveen.paymentservice.facade.PaymentFacade;
import com.skybook.praveen.paymentservice.repository.InvoiceRepository;
import com.skybook.praveen.paymentservice.repository.PaymentHistoryRepository;
import com.skybook.praveen.paymentservice.repository.PaymentRepository;
import com.skybook.praveen.paymentservice.repository.PaymentTransactionRepository;
import com.skybook.praveen.paymentservice.repository.RefundRepository;
import com.skybook.praveen.paymentservice.service.ActionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money-critical races (design doc section 18): a double capture must
 * mint exactly one invoice, and concurrent refunds can never push
 * refundedAmount past capturedAmount. Real transactions, real threads,
 * real gateway (simulated), real Kafka for the facade's events.
 */
class PaymentConcurrencyTest extends AbstractPaymentIntegrationTest {

    private static final AtomicLong BOOKING_SEQ = new AtomicLong(888_000);

    @Autowired
    private PaymentFacade paymentFacade;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private PaymentHistoryRepository historyRepository;

    private final ActionContext ctx = ActionContext.user("concurrency-test");

    @BeforeEach
    void cleanUp() {
        invoiceRepository.deleteAll();
        refundRepository.deleteAll();
        transactionRepository.deleteAll();
        historyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private Payment authorizedPayment() {
        return paymentRepository.save(Payment.builder()
                .paymentReference("PAY-2026-CONC" + (char) ('A' + (BOOKING_SEQ.get() % 26)) + "A")
                .bookingId(BOOKING_SEQ.incrementAndGet())
                .bookingReference("SBCONC")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .capturedAmount(BigDecimal.ZERO)
                .refundedAmount(BigDecimal.ZERO)
                .status(PaymentStatus.AUTHORIZED)
                .gatewayReference("SIM-preauth")
                .build());
    }

    private Payment capturedPayment() {
        Payment payment = authorizedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedAmount(new BigDecimal("100.00"));
        return paymentRepository.save(payment);
    }

    private List<Throwable> race(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        for (Callable<Object> call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();

        List<Throwable> failures = new ArrayList<>();
        for (Future<Object> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        pool.shutdown();
        return failures;
    }

    @Test
    void doubleCaptureMintsExactlyOneInvoice() throws Exception {

        Payment payment = authorizedPayment();
        Long id = payment.getId();

        List<Throwable> failures = race(List.of(
                () -> paymentFacade.capture(id, ctx),
                () -> paymentFacade.capture(id, ctx)
        ));

        // Exactly one winner; the loser lost for a legitimate reason.
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOfAny(
                PaymentConflictException.class,           // saw CAPTURED at beginCapture
                IllegalStateException.class,              // CAPTURED -> CAPTURED transition
                OptimisticLockingFailureException.class,  // @Version collision at commit
                // The DB backstop: both threads flush together, inserts run
                // before updates, and the loser dies on the invoices unique
                // FK - design doc section 3.1.1's "DB-enforced rules survive
                // buggy code" argument, observed live in the first run.
                DataIntegrityViolationException.class
        );

        Payment after = paymentRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(after.getCapturedAmount()).isEqualByComparingTo("100.00");

        // THE invariant: one capture -> one invoice, never two.
        assertThat(invoiceRepository.count()).isEqualTo(1);

        // The loser's ledger row rolled back with its transaction - exactly
        // one SUCCEEDED CAPTURE row survives.
        long successfulCaptures = transactionRepository.findByPaymentIdOrderByOccurredAtAsc(id).stream()
                .filter(t -> t.getType() == com.skybook.praveen.paymentservice.enums.TransactionType.CAPTURE
                        && t.getStatus() == com.skybook.praveen.paymentservice.enums.TransactionStatus.SUCCEEDED)
                .count();
        assertThat(successfulCaptures).isEqualTo(1);
    }

    @Test
    void concurrentFullRefundsCannotExceedTheCapturedAmount() throws Exception {

        Payment payment = capturedPayment();
        Long id = payment.getId();

        List<Throwable> failures = race(List.of(
                () -> paymentFacade.refund(id,
                        new com.skybook.praveen.paymentservice.dto.request.RefundRequest(null, "race A"), ctx),
                () -> paymentFacade.refund(id,
                        new com.skybook.praveen.paymentservice.dto.request.RefundRequest(null, "race B"), ctx)
        ));

        // Exactly one refund goes through.
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOfAny(
                PaymentConflictException.class,          // remaining amount already zero at beginRefund
                IllegalStateException.class,             // REFUNDED is terminal - loser's transition rejected
                OptimisticLockingFailureException.class  // @Version collision
        );

        Payment after = paymentRepository.findById(id).orElseThrow();
        // Invariant: refundedAmount <= capturedAmount, exactly one settlement.
        assertThat(after.getRefundedAmount()).isEqualByComparingTo("100.00");
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        long completedRefunds = refundRepository.findByPaymentId(id).stream()
                .filter(r -> r.getStatus() == RefundStatus.COMPLETED)
                .count();
        assertThat(completedRefunds).isEqualTo(1);
    }
}
