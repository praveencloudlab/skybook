package com.skybook.praveen.paymentservice.service.impl;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.domain.CurrencyValidator;
import com.skybook.praveen.paymentservice.domain.PaymentReferenceGenerator;
import com.skybook.praveen.paymentservice.domain.PaymentStateMachine;
import com.skybook.praveen.paymentservice.domain.PaymentValidator;
import com.skybook.praveen.paymentservice.dto.request.CreatePaymentRequest;
import com.skybook.praveen.paymentservice.dto.request.FareLineRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.PaymentTransaction;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.TransactionStatus;
import com.skybook.praveen.paymentservice.enums.TransactionType;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import com.skybook.praveen.paymentservice.repository.PaymentHistoryRepository;
import com.skybook.praveen.paymentservice.repository.PaymentRepository;
import com.skybook.praveen.paymentservice.repository.PaymentTransactionRepository;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final ActionContext CTX = ActionContext.user("test-req");

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;
    @Mock
    private InvoiceServiceImpl invoiceService;

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        // Real domain collaborators - only repositories + invoice service mocked.
        paymentService = new PaymentServiceImpl(
                paymentRepository, paymentTransactionRepository, paymentHistoryRepository,
                new PaymentStateMachine(), new PaymentValidator(),
                new CurrencyValidator("USD,GBP,EUR,INR"), new PaymentReferenceGenerator(),
                invoiceService);

        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentRepository.findByPaymentReference(anyString())).thenReturn(Optional.empty());
        lenient().when(paymentTransactionRepository.findByTransactionReference(anyString()))
                .thenReturn(Optional.empty());
    }

    private Payment payment(PaymentStatus status) {
        Payment payment = Payment.builder()
                .id(1L).paymentReference("PAY-2026-TESTAA")
                .bookingId(42L).bookingReference("SBTEST")
                .amount(new BigDecimal("100.00")).currency("USD")
                .capturedAmount(BigDecimal.ZERO).refundedAmount(BigDecimal.ZERO)
                .status(status).method(PaymentMethod.CARD)
                .build();
        if (status != PaymentStatus.PENDING && status != PaymentStatus.AUTHORIZATION_FAILED) {
            payment.setGatewayReference("SIM-existing");
        }
        return payment;
    }

    private GatewayResult success() {
        return GatewayResult.simulated(true, "SIM-new-ref", "SIM_OK", "ok", new BigDecimal("100.00"), 5);
    }

    private GatewayResult failure(String code) {
        return GatewayResult.simulated(false, null, code, "declined", new BigDecimal("100.00"), 5);
    }

    // ---------------------------------------------------------------
    // create
    // ---------------------------------------------------------------

    @Nested
    class Create {

        private final CreatePaymentRequest request = new CreatePaymentRequest(
                42L, "SBTEST", new BigDecimal("260.00"), "USD", PaymentMethod.CARD,
                List.of(new FareLineRequest("FLEXI", new BigDecimal("100.00")),
                        new FareLineRequest("SAVER", new BigDecimal("160.00"))));

        @Test
        void createsPendingPaymentWithSerializedFareBreakdown() {
            when(paymentRepository.existsByBookingId(42L)).thenReturn(false);

            PaymentService.CreationResult result = paymentService.create(request, "idem-1");

            assertThat(result.replay()).isFalse();
            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            Payment saved = captor.getValue();
            assertThat(saved.getPaymentReference()).matches("PAY-\\d{4}-[A-Z2-9]{6}");
            assertThat(saved.getFareBreakdown()).isEqualTo("FLEXI:100.00;SAVER:160.00");
            assertThat(saved.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(saved.getHistory()).extracting("historyType")
                    .containsExactly(PaymentHistoryType.PAYMENT_CREATED);
            assertThat(saved.getHistory().getFirst().getActor()).isEqualTo("USER");
        }

        @Test
        void idempotencyKeyReplayReturnsTheOriginalWithoutSaving() {
            Payment existing = payment(PaymentStatus.PENDING);
            when(paymentRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

            PaymentService.CreationResult result = paymentService.create(request, "idem-1");

            assertThat(result.replay()).isTrue();
            assertThat(result.payment().paymentReference()).isEqualTo("PAY-2026-TESTAA");
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void duplicateBookingIsRejected() {
            when(paymentRepository.existsByBookingId(42L)).thenReturn(true);

            assertThatThrownBy(() -> paymentService.create(request, null))
                    .isInstanceOf(PaymentConflictException.class)
                    .hasMessageContaining("42");
        }

        @Test
        void unsupportedCurrencyAndMethodAreRejected() {
            when(paymentRepository.existsByBookingId(42L)).thenReturn(false);

            assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(
                    42L, "SBTEST", BigDecimal.TEN, "JPY", null, null), null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(
                    42L, "SBTEST", BigDecimal.TEN, "USD", PaymentMethod.PAYPAL, null), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------
    // createFromBookingEvent
    // ---------------------------------------------------------------

    @Nested
    class CreateFromBookingEvent {

        private BookingEvent event() {
            return BookingEvent.builder()
                    .bookingId(42L).bookingReference("SBTEST")
                    .totalFare(new BigDecimal("180.00")).currency("USD")
                    .passengers(List.of(
                            BookingEventPassenger.builder().name("A").fareType("FLEXI")
                                    .fare(new BigDecimal("100.00")).build(),
                            BookingEventPassenger.builder().name("B").fareType("SAVER")
                                    .fare(new BigDecimal("80.00")).build()))
                    .build();
        }

        @Test
        void buildsThePaymentFromTheEnrichedEvent() {
            when(paymentRepository.findByBookingId(42L)).thenReturn(Optional.empty());

            paymentService.createFromBookingEvent(event());

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            Payment saved = captor.getValue();
            assertThat(saved.getAmount()).isEqualByComparingTo("180.00");
            assertThat(saved.getFareBreakdown()).isEqualTo("FLEXI:100.00;SAVER:80.00");
            // Legacy event (no per-passenger surcharges): the aggregates stay
            // null - the event doesn't know, so the payment must not claim.
            assertThat(saved.getBaseFareTotal()).isNull();
            assertThat(saved.getSeatSurchargeTotal()).isNull();
            assertThat(saved.getHistory().getFirst().getActor()).isEqualTo("KAFKA");
            assertThat(saved.getHistory().getFirst().getSource()).isEqualTo("BOOKING_EVENT");
        }

        @Test
        void seatSurchargesAggregateIntoTheChargeComposition() {
            // SEAT_SELECTION_MODULE.md §10: seatSurchargeTotal = sum of the
            // CHARGED surcharges, baseFareTotal = amount minus that sum, and
            // the fareBreakdown string stays byte-identical to the old format.
            when(paymentRepository.findByBookingId(42L)).thenReturn(Optional.empty());
            BookingEvent enriched = event();
            enriched.getPassengers().get(0).setSeatSurcharge(new BigDecimal("12.00"));
            enriched.getPassengers().get(1).setSeatSurcharge(new BigDecimal("0.00"));

            paymentService.createFromBookingEvent(enriched);

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            Payment saved = captor.getValue();
            assertThat(saved.getSeatSurchargeTotal()).isEqualByComparingTo("12.00");
            assertThat(saved.getBaseFareTotal()).isEqualByComparingTo("168.00");
            assertThat(saved.getFareBreakdown()).isEqualTo("FLEXI:100.00;SAVER:80.00");
        }

        @Test
        void duplicateEventIsIdempotentByBookingId() {
            when(paymentRepository.findByBookingId(42L)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

            PaymentResponse response = paymentService.createFromBookingEvent(event());

            assertThat(response.paymentReference()).isEqualTo("PAY-2026-TESTAA");
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void eventWithoutBookingIdIsRejected() {
            BookingEvent lean = event();
            lean.setBookingId(null);

            assertThatThrownBy(() -> paymentService.createFromBookingEvent(lean))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bookingId");
        }
    }

    // ---------------------------------------------------------------
    // Authorize
    // ---------------------------------------------------------------

    @Nested
    class Authorize {

        @Test
        void beginAuthorizeReturnsGatewayInputsForPendingPayment() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

            PaymentService.AuthorizationContext ctx = paymentService.beginAuthorize(1L);

            assertThat(ctx.paymentReference()).isEqualTo("PAY-2026-TESTAA");
            assertThat(ctx.amount()).isEqualByComparingTo("100.00");
        }

        @Test
        void beginAuthorizeRejectsCapturedPayment() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.CAPTURED)));

            assertThatThrownBy(() -> paymentService.beginAuthorize(1L))
                    .isInstanceOf(PaymentConflictException.class);
        }

        @Test
        void successfulAuthorizationTransitionsAndAppendsLedgerRow() {
            Payment payment = payment(PaymentStatus.PENDING);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordAuthorizationResult(1L, success(), CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.getGatewayReference()).isEqualTo("SIM-new-ref");
            ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
            verify(paymentTransactionRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(TransactionType.AUTHORIZE);
            assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
            assertThat(captor.getValue().getRawGatewayPayload()).isNotBlank();
        }

        @Test
        void declinedAuthorizationRecordsFailureStateAndLedgerRow() {
            Payment payment = payment(PaymentStatus.PENDING);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordAuthorizationResult(1L, failure("SIM_DECLINED"), CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZATION_FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("declined");
            ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
            verify(paymentTransactionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        void retryAfterFailureWalksBackThroughPending() {
            Payment payment = payment(PaymentStatus.AUTHORIZATION_FAILED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordAuthorizationResult(1L, success(), CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.getFailureReason()).isNull();
            // Two history entries: the PENDING re-entry, then AUTHORIZED.
            assertThat(payment.getHistory()).hasSize(2);
        }
    }

    // ---------------------------------------------------------------
    // Capture / Cancel
    // ---------------------------------------------------------------

    @Nested
    class CaptureAndCancel {

        @Test
        void successfulCaptureSetsAmountsAndCreatesTheInvoiceInTheSameCall() {
            Payment payment = payment(PaymentStatus.AUTHORIZED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordCaptureResult(1L, success(), CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.getCapturedAmount()).isEqualByComparingTo("100.00");
            verify(invoiceService).createForCapturedPayment(payment);
        }

        @Test
        void failedCaptureLeavesTheAuthorizationAlive() {
            Payment payment = payment(PaymentStatus.AUTHORIZED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordCaptureResult(1L, failure("SIM_CAPTURE_FAILED"), CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURE_FAILED);
            assertThat(payment.getCapturedAmount()).isEqualByComparingTo("0");
            verify(invoiceService, never()).createForCapturedPayment(any());
        }

        @Test
        void beginCancelSignalsVoidOnlyWhenAuthorized() {
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.AUTHORIZED)));
            assertThat(paymentService.beginCancel(1L).requiresVoid()).isTrue();

            when(paymentRepository.findById(2L)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
            assertThat(paymentService.beginCancel(2L).requiresVoid()).isFalse();
        }

        @Test
        void cancellationWithoutVoidWritesNoLedgerRow() {
            Payment payment = payment(PaymentStatus.PENDING);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordCancellation(1L, null, CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            verify(paymentTransactionRepository, never()).save(any());
        }

        @Test
        void cancellationWithVoidAppendsAVoidLedgerRow() {
            Payment payment = payment(PaymentStatus.AUTHORIZED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            paymentService.recordCancellation(1L, success(), CTX);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
            verify(paymentTransactionRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(TransactionType.VOID);
        }
    }
}
