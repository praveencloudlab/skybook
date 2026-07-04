package com.skybook.praveen.paymentservice.service.impl;

import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.domain.PaymentReferenceGenerator;
import com.skybook.praveen.paymentservice.domain.PaymentStateMachine;
import com.skybook.praveen.paymentservice.domain.PaymentValidator;
import com.skybook.praveen.paymentservice.domain.RefundCalculator;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.Refund;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.enums.TransactionType;
import com.skybook.praveen.paymentservice.exception.RefundNotFoundException;
import com.skybook.praveen.paymentservice.mapper.RefundMapper;
import com.skybook.praveen.paymentservice.repository.RefundRepository;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private static final int MAX_REFERENCE_ATTEMPTS = 10;

    private final RefundRepository refundRepository;

    private final PaymentStateMachine stateMachine;
    private final PaymentValidator paymentValidator;
    private final RefundCalculator refundCalculator;
    private final PaymentReferenceGenerator referenceGenerator;

    // Aggregate lookups + ledger appends live in one class (inventory precedent).
    private final PaymentServiceImpl paymentService;

    @Override
    @Transactional
    public RefundContext beginRefund(Long paymentId, RefundRequest request, ActionContext ctx) {

        Payment payment = paymentService.find(paymentId);

        // Which fare lines are being refunded: the request's subset, or the
        // payment's full stored breakdown.
        List<RefundCalculator.FareLine> lines = request.fareLines() != null && !request.fareLines().isEmpty()
                ? request.fareLines().stream()
                        .map(line -> new RefundCalculator.FareLine(line.fareType(), line.amount()))
                        .toList()
                : RefundCalculator.parse(payment.getFareBreakdown(),
                        payment.getCapturedAmount().subtract(payment.getRefundedAmount()));

        RefundCalculator.RefundComputation computation = refundCalculator.compute(lines);

        paymentValidator.validateRefundable(payment, computation.refundAmount());

        Refund refund = Refund.builder()
                .payment(payment)
                .refundReference(uniqueRefundReference())
                .amount(computation.refundAmount())
                .cancellationFee(computation.cancellationFee())
                .reason(request.reason())
                .build();

        stateMachine.recordHistory(payment, PaymentHistoryType.REFUND_REQUESTED,
                ctx.actor(), ctx.source(), ctx.correlationId(),
                "Refund of " + computation.refundAmount() + " requested (fee withheld: "
                        + computation.cancellationFee() + ")");

        Refund saved = refundRepository.save(refund);
        log.info("Refund {} requested on payment {}: {} (fee {})",
                saved.getRefundReference(), payment.getPaymentReference(),
                computation.refundAmount(), computation.cancellationFee());

        return new RefundContext(saved.getId(), payment.getGatewayReference(), computation.refundAmount());
    }

    @Override
    @Transactional
    public RefundResponse completeRefund(Long refundId, GatewayResult result, ActionContext ctx) {

        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException(refundId));
        Payment payment = refund.getPayment();

        paymentService.appendTransaction(payment, TransactionType.REFUND, result, refund.getAmount());

        if (result.success()) {
            refund.setStatus(RefundStatus.COMPLETED);
            refund.setCompletedAt(LocalDateTime.now());

            payment.setRefundedAmount(payment.getRefundedAmount().add(refund.getAmount()));

            // Fully refunded when everything captured has been settled -
            // either returned to the customer or withheld as fees - summed
            // across ALL completed refunds, not just this one.
            java.math.BigDecimal settled = refund.getAmount().add(refund.getCancellationFee());
            for (Refund earlier : payment.getRefunds()) {
                if (earlier.getStatus() == RefundStatus.COMPLETED && !earlier.getId().equals(refund.getId())) {
                    settled = settled.add(earlier.getAmount()).add(earlier.getCancellationFee());
                }
            }
            boolean fullyRefunded = settled.compareTo(payment.getCapturedAmount()) >= 0;

            stateMachine.transition(payment,
                    fullyRefunded ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
                    PaymentHistoryType.REFUND_COMPLETED,
                    ctx.actor(), ctx.source(), ctx.correlationId(),
                    "Refund " + refund.getRefundReference() + " completed: " + refund.getAmount());

            log.info("Refund {} completed on payment {}", refund.getRefundReference(), payment.getPaymentReference());
        } else {
            refund.setStatus(RefundStatus.FAILED);
            stateMachine.recordHistory(payment, PaymentHistoryType.REFUND_FAILED,
                    ctx.actor(), ctx.source(), ctx.correlationId(),
                    "[" + result.responseCode() + "] " + result.message());
            log.warn("Refund {} FAILED on payment {}: {}",
                    refund.getRefundReference(), payment.getPaymentReference(), result.message());
        }

        return RefundMapper.toResponse(refund);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundResponse> getAllRefunds() {
        return refundRepository.findAll().stream().map(RefundMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RefundResponse getRefund(Long id) {
        return RefundMapper.toResponse(refundRepository.findById(id)
                .orElseThrow(() -> new RefundNotFoundException(id)));
    }

    private String uniqueRefundReference() {
        for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
            String reference = referenceGenerator.refundReference();
            if (refundRepository.findByRefundReference(reference).isEmpty()) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not generate a unique refund reference");
    }
}
