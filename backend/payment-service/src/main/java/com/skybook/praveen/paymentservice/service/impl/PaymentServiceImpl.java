package com.skybook.praveen.paymentservice.service.impl;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.domain.CurrencyValidator;
import com.skybook.praveen.paymentservice.domain.PaymentReferenceGenerator;
import com.skybook.praveen.paymentservice.domain.PaymentStateMachine;
import com.skybook.praveen.paymentservice.domain.PaymentValidator;
import com.skybook.praveen.paymentservice.domain.RefundCalculator;
import com.skybook.praveen.paymentservice.dto.request.CreatePaymentRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentHistoryResponse;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.PaymentTransaction;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.TransactionStatus;
import com.skybook.praveen.paymentservice.enums.TransactionType;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import com.skybook.praveen.paymentservice.exception.PaymentNotFoundException;
import com.skybook.praveen.paymentservice.mapper.PaymentHistoryMapper;
import com.skybook.praveen.paymentservice.mapper.PaymentMapper;
import com.skybook.praveen.paymentservice.repository.PaymentHistoryRepository;
import com.skybook.praveen.paymentservice.repository.PaymentRepository;
import com.skybook.praveen.paymentservice.repository.PaymentTransactionRepository;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final int MAX_REFERENCE_ATTEMPTS = 10;
    private static final String DEFAULT_CURRENCY = "USD";

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;

    private final PaymentStateMachine stateMachine;
    private final PaymentValidator paymentValidator;
    private final CurrencyValidator currencyValidator;
    private final PaymentReferenceGenerator referenceGenerator;

    private final InvoiceServiceImpl invoiceService;

    // ---------------------------------------------------------------
    // Creation
    // ---------------------------------------------------------------

    @Override
    @Transactional
    public CreationResult create(CreatePaymentRequest request, String idempotencyKey) {

        // Idempotent replay: same key -> the original payment, no duplicate.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency-Key replay for payment {}", existing.get().getPaymentReference());
                return new CreationResult(PaymentMapper.toResponse(existing.get()), true);
            }
        }

        if (paymentRepository.existsByBookingId(request.bookingId())) {
            throw new PaymentConflictException("Payment already exists for booking id: " + request.bookingId());
        }

        paymentValidator.validateAmount(request.amount());
        paymentValidator.validateMethodImplemented(request.method());
        currencyValidator.validate(request.currency());

        String fareBreakdown = request.fareLines() == null ? null
                : RefundCalculator.serialize(request.fareLines().stream()
                        .map(line -> new RefundCalculator.FareLine(line.fareType(), line.amount()))
                        .toList());

        Payment payment = Payment.builder()
                .paymentReference(uniquePaymentReference())
                .bookingId(request.bookingId())
                .bookingReference(request.bookingReference())
                .amount(request.amount())
                .currency(request.currency().toUpperCase())
                .method(request.method())
                .idempotencyKey(idempotencyKey)
                .fareBreakdown(fareBreakdown)
                .build();

        stateMachine.recordHistory(payment, PaymentHistoryType.PAYMENT_CREATED,
                "USER", "API", idempotencyKey != null ? idempotencyKey : request.bookingReference(),
                "Payment created for booking " + request.bookingReference());

        Payment saved = paymentRepository.save(payment);
        log.info("Created payment {} for booking {} ({} {})",
                saved.getPaymentReference(), request.bookingReference(), request.amount(), request.currency());

        return new CreationResult(PaymentMapper.toResponse(saved), false);
    }

    @Override
    @Transactional
    public PaymentResponse createFromBookingEvent(BookingEvent event) {

        // Idempotent by bookingId - a redelivered event is a no-op.
        var existing = paymentRepository.findByBookingId(resolveBookingId(event));
        if (existing.isPresent()) {
            log.info("Duplicate CREATED event for booking {} - payment {} already exists",
                    event.getBookingReference(), existing.get().getPaymentReference());
            return PaymentMapper.toResponse(existing.get());
        }

        BigDecimal amount = event.getTotalFare();
        paymentValidator.validateAmount(amount);
        String currency = event.getCurrency() != null ? event.getCurrency() : DEFAULT_CURRENCY;
        currencyValidator.validate(currency);

        Payment payment = Payment.builder()
                .paymentReference(uniquePaymentReference())
                .bookingId(resolveBookingId(event))
                .bookingReference(event.getBookingReference())
                .amount(amount)
                .currency(currency.toUpperCase())
                .fareBreakdown(fareBreakdownFrom(event))
                .build();

        stateMachine.recordHistory(payment, PaymentHistoryType.PAYMENT_CREATED,
                "KAFKA", "BOOKING_EVENT", event.getBookingReference(),
                "Payment auto-created from booking CREATED event");

        Payment saved = paymentRepository.save(payment);
        log.info("Auto-created payment {} for booking {} ({} {})",
                saved.getPaymentReference(), event.getBookingReference(), amount, currency);

        return PaymentMapper.toResponse(saved);
    }

    // ---------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getById(Long id) {
        return PaymentMapper.toResponse(find(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String paymentReference) {
        return PaymentMapper.toResponse(paymentRepository.findByPaymentReference(paymentReference)
                .orElseThrow(() -> PaymentNotFoundException.byReference(paymentReference)));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getByBookingId(Long bookingId) {
        return PaymentMapper.toResponse(paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> PaymentNotFoundException.byBooking(bookingId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> getHistory(Long paymentId) {
        find(paymentId); // 404 for unknown payment rather than an empty list
        return paymentHistoryRepository.findByPaymentIdOrderByChangedAtAsc(paymentId)
                .stream().map(PaymentHistoryMapper::toResponse).toList();
    }

    // ---------------------------------------------------------------
    // Authorize
    // ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AuthorizationContext beginAuthorize(Long id) {
        Payment payment = find(id);
        paymentValidator.validateAuthorizable(payment);
        return new AuthorizationContext(payment.getId(), payment.getPaymentReference(),
                payment.getAmount(), payment.getCurrency());
    }

    @Override
    @Transactional
    public PaymentResponse recordAuthorizationResult(Long id, GatewayResult result, ActionContext ctx) {

        Payment payment = find(id);
        appendTransaction(payment, TransactionType.AUTHORIZE, result, payment.getAmount());

        if (result.success()) {
            payment.setGatewayReference(result.gatewayReference());
            payment.setFailureReason(null);
            // AUTHORIZATION_FAILED -> PENDING -> AUTHORIZED is the retry
            // path; a retried authorize arrives here still AUTHORIZATION_FAILED.
            if (payment.getStatus() == PaymentStatus.AUTHORIZATION_FAILED) {
                stateMachine.transition(payment, PaymentStatus.PENDING, PaymentHistoryType.PAYMENT_CREATED,
                        ctx.actor(), ctx.source(), ctx.correlationId(), "Re-entering after failed authorization");
            }
            stateMachine.transition(payment, PaymentStatus.AUTHORIZED, PaymentHistoryType.AUTHORIZED,
                    ctx.actor(), ctx.source(), ctx.correlationId(),
                    "Authorized at gateway: " + result.gatewayReference());
        } else {
            payment.setFailureReason(result.message());
            if (payment.getStatus() == PaymentStatus.PENDING) {
                stateMachine.transition(payment, PaymentStatus.AUTHORIZATION_FAILED,
                        PaymentHistoryType.AUTHORIZATION_FAILED,
                        ctx.actor(), ctx.source(), ctx.correlationId(),
                        "[" + result.responseCode() + "] " + result.message());
            } else {
                // Already AUTHORIZATION_FAILED (failed retry) - record history only.
                stateMachine.recordHistory(payment, PaymentHistoryType.AUTHORIZATION_FAILED,
                        ctx.actor(), ctx.source(), ctx.correlationId(),
                        "Retry failed: [" + result.responseCode() + "] " + result.message());
            }
        }

        return PaymentMapper.toResponse(payment);
    }

    // ---------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CaptureContext beginCapture(Long id) {
        Payment payment = find(id);
        paymentValidator.validateCapturable(payment);
        return new CaptureContext(payment.getId(), payment.getGatewayReference(), payment.getAmount());
    }

    @Override
    @Transactional
    public PaymentResponse recordCaptureResult(Long id, GatewayResult result, ActionContext ctx) {

        Payment payment = find(id);
        appendTransaction(payment, TransactionType.CAPTURE, result, payment.getAmount());

        if (result.success()) {
            payment.setCapturedAmount(payment.getAmount());
            payment.setFailureReason(null);
            stateMachine.transition(payment, PaymentStatus.CAPTURED, PaymentHistoryType.CAPTURED,
                    ctx.actor(), ctx.source(), ctx.correlationId(),
                    "Captured " + payment.getAmount() + " " + payment.getCurrency());
            // Invoice exists iff CAPTURED - same transaction (design doc section 7).
            invoiceService.createForCapturedPayment(payment);
        } else {
            payment.setFailureReason(result.message());
            if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
                stateMachine.transition(payment, PaymentStatus.CAPTURE_FAILED, PaymentHistoryType.CAPTURE_FAILED,
                        ctx.actor(), ctx.source(), ctx.correlationId(),
                        "[" + result.responseCode() + "] " + result.message());
            } else {
                stateMachine.recordHistory(payment, PaymentHistoryType.CAPTURE_FAILED,
                        ctx.actor(), ctx.source(), ctx.correlationId(),
                        "Retry failed: [" + result.responseCode() + "] " + result.message());
            }
        }

        return PaymentMapper.toResponse(payment);
    }

    // ---------------------------------------------------------------
    // Cancel
    // ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CancelContext beginCancel(Long id) {
        Payment payment = find(id);
        paymentValidator.validateCancellable(payment);
        boolean requiresVoid = payment.getStatus() == PaymentStatus.AUTHORIZED
                || payment.getStatus() == PaymentStatus.CAPTURE_FAILED;
        return new CancelContext(payment.getId(), requiresVoid, payment.getGatewayReference());
    }

    @Override
    @Transactional
    public PaymentResponse recordCancellation(Long id, GatewayResult voidResult, ActionContext ctx) {

        Payment payment = find(id);

        if (voidResult != null) {
            appendTransaction(payment, TransactionType.VOID, voidResult, payment.getAmount());
        }

        stateMachine.transition(payment, PaymentStatus.CANCELLED, PaymentHistoryType.CANCELLED,
                ctx.actor(), ctx.source(), ctx.correlationId(),
                voidResult != null ? "Cancelled - authorization voided at gateway" : "Cancelled before authorization");

        return PaymentMapper.toResponse(payment);
    }

    // ---------------------------------------------------------------
    // Shared internals (package-private for RefundServiceImpl)
    // ---------------------------------------------------------------

    Payment find(Long id) {
        return paymentRepository.findById(id).orElseThrow(() -> PaymentNotFoundException.byId(id));
    }

    /** Every gateway interaction becomes a ledger row, success or failure (design doc section 3.2). */
    PaymentTransaction appendTransaction(Payment payment, TransactionType type,
                                         GatewayResult result, BigDecimal amount) {
        PaymentTransaction transaction = PaymentTransaction.builder()
                .payment(payment)
                .transactionReference(uniqueTransactionReference())
                .type(type)
                .status(result.success() ? TransactionStatus.SUCCEEDED : TransactionStatus.FAILED)
                .amount(amount)
                .gatewayReference(result.gatewayReference())
                .gatewayResponseCode(result.responseCode())
                .gatewayMessage(result.message())
                .rawGatewayPayload(result.rawPayload())
                .durationMs(result.durationMs())
                .build();
        return paymentTransactionRepository.save(transaction);
    }

    private String uniquePaymentReference() {
        for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
            String reference = referenceGenerator.paymentReference();
            if (paymentRepository.findByPaymentReference(reference).isEmpty()) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not generate a unique payment reference");
    }

    private String uniqueTransactionReference() {
        for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
            String reference = referenceGenerator.transactionReference();
            if (paymentTransactionRepository.findByTransactionReference(reference).isEmpty()) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not generate a unique transaction reference");
    }

    private static Long resolveBookingId(BookingEvent event) {
        if (event.getBookingId() == null) {
            // Pre-enrichment events (before bookingId was added to the
            // contract) cannot be turned into payments - skip loudly.
            throw new IllegalArgumentException("Booking event for " + event.getBookingReference()
                    + " carries no bookingId - produced by an old booking-service version");
        }
        return event.getBookingId();
    }

    private static String fareBreakdownFrom(BookingEvent event) {
        if (event.getPassengers() == null || event.getPassengers().isEmpty()) {
            return null;
        }
        List<RefundCalculator.FareLine> lines = new ArrayList<>();
        for (BookingEventPassenger passenger : event.getPassengers()) {
            if (passenger.getFareType() != null && passenger.getFare() != null) {
                lines.add(new RefundCalculator.FareLine(passenger.getFareType(), passenger.getFare()));
            }
        }
        return lines.isEmpty() ? null : RefundCalculator.serialize(lines);
    }
}
