package com.skybook.praveen.paymentservice.exception;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(Long paymentId) {
        super("Invoice not found for payment id: " + paymentId
                + " (invoices exist only after capture)");
    }
}
