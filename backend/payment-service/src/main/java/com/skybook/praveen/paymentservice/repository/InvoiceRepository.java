package com.skybook.praveen.paymentservice.repository;

import com.skybook.praveen.paymentservice.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByPaymentId(Long paymentId);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
}
