package com.skybook.praveen.paymentservice.controller;

import com.skybook.praveen.paymentservice.dto.request.CreatePaymentRequest;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentHistoryResponse;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.facade.PaymentFacade;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.PaymentService;
import com.skybook.praveen.security.SecurityAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Orchestrated operations (authorize/capture/cancel/refund - gateway +
 * events) go through the facade; creation and reads call the service
 * directly - same split as the sibling services.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentFacade paymentFacade;

    /** Idempotency-Key replay returns 200 with the original payment (design doc section 8). */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        PaymentService.CreationResult result = paymentService.create(request, idempotencyKey);
        return ResponseEntity
                .status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.payment());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getById(@PathVariable Long id) {
        PaymentResponse payment = paymentService.getById(id);
        SecurityAccess.requireOwnerOrAdmin(payment.ownerSubject());
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/reference/{reference}")
    public ResponseEntity<PaymentResponse> getByReference(@PathVariable String reference) {
        PaymentResponse payment = paymentService.getByReference(reference);
        SecurityAccess.requireOwnerOrAdmin(payment.ownerSubject());
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<PaymentResponse> getByBookingId(@PathVariable Long bookingId) {
        PaymentResponse payment = paymentService.getByBookingId(bookingId);
        SecurityAccess.requireOwnerOrAdmin(payment.ownerSubject());
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<PaymentHistoryResponse>> getHistory(@PathVariable Long id) {
        SecurityAccess.requireOwnerOrAdmin(paymentService.ownerSubjectOf(id));
        return ResponseEntity.ok(paymentService.getHistory(id));
    }

    @PatchMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorize(@PathVariable Long id) {
        SecurityAccess.requireOwnerOrAdmin(paymentService.ownerSubjectOf(id));
        return ResponseEntity.ok(paymentFacade.authorize(id, ActionContext.user("payment-" + id)));
    }

    @PatchMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capture(@PathVariable Long id) {
        SecurityAccess.requireOwnerOrAdmin(paymentService.ownerSubjectOf(id));
        return ResponseEntity.ok(paymentFacade.capture(id, ActionContext.user("payment-" + id)));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<PaymentResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(paymentFacade.cancel(id, ActionContext.user("payment-" + id)));
    }

    @PatchMapping("/{id}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable Long id, @Valid @RequestBody(required = false) RefundRequest request) {
        RefundRequest effective = request != null ? request : new RefundRequest(null, null);
        return ResponseEntity.ok(paymentFacade.refund(id, effective, ActionContext.user("payment-" + id)));
    }
}
