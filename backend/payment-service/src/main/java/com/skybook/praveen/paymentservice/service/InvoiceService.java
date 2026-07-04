package com.skybook.praveen.paymentservice.service;

import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;

public interface InvoiceService {

    InvoiceResponse getByPaymentId(Long paymentId);
}
