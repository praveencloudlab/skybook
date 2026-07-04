package com.skybook.praveen.paymentservice.service.impl;

import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.domain.CurrencyValidator;
import com.skybook.praveen.paymentservice.domain.PaymentReferenceGenerator;
import com.skybook.praveen.paymentservice.domain.PaymentStateMachine;
import com.skybook.praveen.paymentservice.domain.PaymentValidator;
import com.skybook.praveen.paymentservice.domain.RefundCalculator;
import com.skybook.praveen.paymentservice.dto.request.FareLineRequest;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.PaymentTransaction;
import com.skybook.praveen.paymentservice.entity.Refund;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import com.skybook.praveen.paymentservice.repository.PaymentHistoryRepository;
import com.skybook.praveen.paymentservice.repository.PaymentRepository;
import com.skybook.praveen.paymentservice.repository.PaymentTransactionRepository;
import com.skybook.praveen.paymentservice.repository.RefundRepository;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceImplTest {

    private static final ActionContext CTX = ActionContext.kafka("SBTEST");

    @Mock
    private RefundRepository refundRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;
    @Mock
    private InvoiceServiceImpl invoiceService;

    private RefundServiceImpl refundService;

    private Payment payment;

    @BeforeEach
    void setUp() {
        PaymentStateMachine stateMachine = new PaymentStateMachine();
        PaymentValidator paymentValidator = new PaymentValidator();
        PaymentReferenceGenerator referenceGenerator = new PaymentReferenceGenerator();

        // Real PaymentServiceImpl over the same mocks (inventory precedent) -
        // ledger appends and aggregate lookups exercised for real.
        PaymentServiceImpl paymentService = new PaymentServiceImpl(
                paymentRepository, paymentTransactionRepository, paymentHistoryRepository,
                stateMachine, paymentValidator, new CurrencyValidator("USD"),
                referenceGenerator, invoiceService);

        refundService = new RefundServiceImpl(
                refundRepository, stateMachine, paymentValidator,
                new RefundCalculator(new BigDecimal("30")), referenceGenerator, paymentService);

        payment = Payment.builder()
                .id(1L).paymentReference("PAY-2026-TESTAA")
                .bookingId(42L).bookingReference("SBTEST")
                .amount(new BigDecimal("180.00")).currency("USD")
                .capturedAmount(new BigDecimal("180.00")).refundedAmount(BigDecimal.ZERO)
                .status(PaymentStatus.CAPTURED).method(PaymentMethod.CARD)
                .gatewayReference("SIM-auth-ref")
                .fareBreakdown("FLEXI:100.00;SAVER:80.00")
                .build();

        lenient().when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        lenient().when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund refund = inv.getArgument(0);
            if (refund.getId() == null) {
                refund.setId(77L);
            }
            return refund;
        });
        lenient().when(refundRepository.findByRefundReference(anyString())).thenReturn(Optional.empty());
        lenient().when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentTransactionRepository.findByTransactionReference(anyString()))
                .thenReturn(Optional.empty());
    }

    private GatewayResult gatewaySuccess(BigDecimal amount) {
        return GatewayResult.simulated(true, "SIM-auth-ref", "SIM_OK", "refunded", amount, 3);
    }

    @Test
    void beginRefundComputesFareRulesAndCreatesPendingRefund() {

        RefundService.RefundContext context = refundService.beginRefund(1L,
                new RefundRequest(null, "booking cancelled"), CTX);

        // FLEXI 100 full + SAVER 80 at 30% fee = 100 + 56 = 156, fee 24.
        assertThat(context.refundAmount()).isEqualByComparingTo("156.00");

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(captor.capture());
        Refund saved = captor.getValue();
        assertThat(saved.getAmount()).isEqualByComparingTo("156.00");
        assertThat(saved.getCancellationFee()).isEqualByComparingTo("24.00");
        assertThat(saved.getRefundReference()).matches("REF-\\d{4}-[A-Z2-9]{6}");
        assertThat(payment.getHistory()).extracting("historyType")
                .containsExactly(PaymentHistoryType.REFUND_REQUESTED);
    }

    @Test
    void beginRefundWithSubsetLinesIsAPartialRefund() {

        RefundService.RefundContext context = refundService.beginRefund(1L,
                new RefundRequest(List.of(new FareLineRequest("SAVER", new BigDecimal("80.00"))),
                        "one passenger cancelled"), CTX);

        assertThat(context.refundAmount()).isEqualByComparingTo("56.00");
    }

    @Test
    void beginRefundRejectsNonCapturedPayments() {
        payment.setStatus(PaymentStatus.AUTHORIZED);

        assertThatThrownBy(() -> refundService.beginRefund(1L, new RefundRequest(null, null), CTX))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void completedFullRefundTerminatesAtRefundedDespiteWithheldFees() {
        Refund refund = Refund.builder()
                .id(77L).payment(payment).refundReference("REF-2026-TESTAA")
                .amount(new BigDecimal("156.00")).cancellationFee(new BigDecimal("24.00"))
                .status(RefundStatus.PENDING)
                .build();
        when(refundRepository.findById(77L)).thenReturn(Optional.of(refund));

        RefundResponse response = refundService.completeRefund(77L, gatewaySuccess(new BigDecimal("156.00")), CTX);

        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("156.00");
        // 156 refunded + 24 fee = 180 captured -> fully settled -> REFUNDED.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void partialRefundLeavesPaymentPartiallyRefunded() {
        Refund refund = Refund.builder()
                .id(77L).payment(payment).refundReference("REF-2026-TESTAB")
                .amount(new BigDecimal("56.00")).cancellationFee(new BigDecimal("24.00"))
                .status(RefundStatus.PENDING)
                .build();
        when(refundRepository.findById(77L)).thenReturn(Optional.of(refund));

        refundService.completeRefund(77L, gatewaySuccess(new BigDecimal("56.00")), CTX);

        // 56 + 24 = 80 settled of 180 captured -> partial.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void secondPartialRefundThatSettlesEverythingReachesRefunded() {
        payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        payment.setRefundedAmount(new BigDecimal("56.00"));
        Refund earlier = Refund.builder()
                .id(70L).payment(payment).refundReference("REF-2026-TESTAC")
                .amount(new BigDecimal("56.00")).cancellationFee(new BigDecimal("24.00"))
                .status(RefundStatus.COMPLETED).completedAt(LocalDateTime.now())
                .build();
        payment.getRefunds().add(earlier);

        Refund current = Refund.builder()
                .id(77L).payment(payment).refundReference("REF-2026-TESTAD")
                .amount(new BigDecimal("100.00")).cancellationFee(BigDecimal.ZERO)
                .status(RefundStatus.PENDING)
                .build();
        payment.getRefunds().add(current);
        when(refundRepository.findById(77L)).thenReturn(Optional.of(current));

        refundService.completeRefund(77L, gatewaySuccess(new BigDecimal("100.00")), CTX);

        // Settled across refunds: (56+24) + (100+0) = 180 = captured -> REFUNDED.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("156.00");
    }

    @Test
    void failedGatewayRefundMarksTheRefundFailedAndLeavesThePaymentState() {
        Refund refund = Refund.builder()
                .id(77L).payment(payment).refundReference("REF-2026-TESTAE")
                .amount(new BigDecimal("156.00")).cancellationFee(new BigDecimal("24.00"))
                .status(RefundStatus.PENDING)
                .build();
        when(refundRepository.findById(77L)).thenReturn(Optional.of(refund));

        RefundResponse response = refundService.completeRefund(77L,
                GatewayResult.simulated(false, "SIM-auth-ref", "SIM_ERROR", "gateway down",
                        new BigDecimal("156.00"), 3), CTX);

        assertThat(response.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(payment.getHistory()).extracting("historyType")
                .containsExactly(PaymentHistoryType.REFUND_FAILED);
    }
}
