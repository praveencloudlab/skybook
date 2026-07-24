package com.skybook.praveen.paymentservice.controller;

import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;
import com.skybook.praveen.paymentservice.service.InvoiceService;
import com.skybook.praveen.paymentservice.service.PaymentService;
import com.skybook.praveen.security.SecurityAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    /**
     * The passenger's own receipt (§4.4): OWNER-or-ADMIN. 404 with an
     * explanatory message when the payment hasn't been captured yet.
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<InvoiceResponse> getByPaymentId(@PathVariable Long paymentId) {
        SecurityAccess.requireOwnerOrAdmin(paymentService.ownerSubjectOf(paymentId));
        return ResponseEntity.ok(invoiceService.getByPaymentId(paymentId));
    }
}
