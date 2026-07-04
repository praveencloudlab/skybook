package com.skybook.praveen.paymentservice.jpa;

import com.skybook.praveen.paymentservice.entity.Invoice;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.PaymentTransaction;
import com.skybook.praveen.paymentservice.entity.Refund;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.enums.TransactionStatus;
import com.skybook.praveen.paymentservice.enums.TransactionType;
import com.skybook.praveen.paymentservice.repository.InvoiceRepository;
import com.skybook.praveen.paymentservice.repository.PaymentRepository;
import com.skybook.praveen.paymentservice.repository.PaymentTransactionRepository;
import com.skybook.praveen.paymentservice.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ledger children: PaymentTransaction (append-only), Refund, Invoice (one per captured payment). */
class PaymentLedgerJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private EntityManager entityManager;

    private Payment payment;
    private long seq = 5000;

    @BeforeEach
    void setUp() {
        invoiceRepository.deleteAll();
        refundRepository.deleteAll();
        transactionRepository.deleteAll();
        paymentRepository.deleteAll();

        payment = paymentRepository.saveAndFlush(Payment.builder()
                .paymentReference("PAY-2026-LEDGAA")
                .bookingId(++seq)
                .bookingReference("SBLEDG")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build());
    }

    private PaymentTransaction.PaymentTransactionBuilder transaction(String reference) {
        return PaymentTransaction.builder()
                .payment(payment)
                .transactionReference(reference)
                .type(TransactionType.AUTHORIZE)
                .status(TransactionStatus.SUCCEEDED)
                .amount(new BigDecimal("100.00"))
                .gatewayReference("SIM-abc")
                .gatewayResponseCode("SIM_OK")
                .rawGatewayPayload("{\"gateway\":\"SIMULATED\"}");
    }

    @Test
    void transactionReferenceIsUnique() {
        transactionRepository.saveAndFlush(transaction("TXN-2026-UNIQAA").build());

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(
                transaction("TXN-2026-UNIQAA").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedInteractionsAreFirstClassLedgerRows() {
        transactionRepository.saveAndFlush(transaction("TXN-2026-FAILAA")
                .status(TransactionStatus.FAILED)
                .gatewayResponseCode("SIM_DECLINED")
                .build());

        var rows = transactionRepository.findByPaymentIdOrderByOccurredAtAsc(payment.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(rows.getFirst().getRawGatewayPayload()).contains("SIMULATED");
    }

    @Test
    void ledgerReadsBackInOccurredAtOrder() {
        transactionRepository.saveAndFlush(transaction("TXN-2026-ORDAAB")
                .type(TransactionType.CAPTURE)
                .occurredAt(LocalDateTime.now().plusSeconds(1)).build());
        transactionRepository.saveAndFlush(transaction("TXN-2026-ORDAAA")
                .occurredAt(LocalDateTime.now().minusSeconds(1)).build());

        assertThat(transactionRepository.findByPaymentIdOrderByOccurredAtAsc(payment.getId()))
                .extracting(PaymentTransaction::getType)
                .containsExactly(TransactionType.AUTHORIZE, TransactionType.CAPTURE);
    }

    @Test
    void refundDefaultsToPendingWithZeroFee() {
        Refund saved = refundRepository.saveAndFlush(Refund.builder()
                .payment(payment)
                .refundReference("REF-2026-DEFAAA")
                .amount(new BigDecimal("70.00"))
                .build());

        assertThat(saved.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(saved.getCancellationFee()).isEqualByComparingTo("0");
        assertThat(saved.getCompletedAt()).isNull();
    }

    @Test
    void refundStatusIsStoredAsString() {
        Refund saved = refundRepository.saveAndFlush(Refund.builder()
                .payment(payment).refundReference("REF-2026-STRAAA")
                .amount(new BigDecimal("70.00")).build());

        String stored = (String) entityManager.createNativeQuery(
                        "select status from refunds where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(stored).isEqualTo("PENDING");
    }

    @Test
    void onePaymentGetsExactlyOneInvoice() {
        invoiceRepository.saveAndFlush(invoice("INV-2026-000101"));

        // The DB enforces the "invoice exists iff CAPTURED, once" invariant's
        // uniqueness half via the unique FK (design doc section 3.1.1).
        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(invoice("INV-2026-000102")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoiceNumberIsUnique() {
        invoiceRepository.saveAndFlush(invoice("INV-2026-000103"));

        Payment other = paymentRepository.saveAndFlush(Payment.builder()
                .paymentReference("PAY-2026-LEDGAB").bookingId(++seq).bookingReference("SBLED2")
                .amount(BigDecimal.TEN).currency("USD").build());
        Invoice duplicateNumber = Invoice.builder()
                .payment(other).invoiceNumber("INV-2026-000103").bookingReference("SBLED2")
                .subtotal(BigDecimal.TEN).grandTotal(BigDecimal.TEN).currency("USD").build();

        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(duplicateNumber))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoicePrePersistDefaultsTaxDiscountAndIssuedAt() {
        Invoice saved = invoiceRepository.saveAndFlush(invoice("INV-2026-000104"));

        assertThat(saved.getTaxAmount()).isEqualByComparingTo("0");
        assertThat(saved.getDiscount()).isEqualByComparingTo("0");
        assertThat(saved.getIssuedAt()).isNotNull();
        assertThat(invoiceRepository.findByPaymentId(payment.getId())).isPresent();
        assertThat(invoiceRepository.findByInvoiceNumber("INV-2026-000104")).isPresent();
    }

    private Invoice invoice(String number) {
        return Invoice.builder()
                .payment(payment)
                .invoiceNumber(number)
                .bookingReference("SBLEDG")
                .subtotal(new BigDecimal("100.00"))
                .grandTotal(new BigDecimal("100.00"))
                .currency("USD")
                .build();
    }
}
