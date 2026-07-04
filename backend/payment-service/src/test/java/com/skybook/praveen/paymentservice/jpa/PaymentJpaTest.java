package com.skybook.praveen.paymentservice.jpa;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.PaymentHistory;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.repository.PaymentHistoryRepository;
import com.skybook.praveen.paymentservice.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;
    @Autowired
    private EntityManager entityManager;

    private long bookingSeq = 1000;

    @BeforeEach
    void cleanUp() {
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private Payment.PaymentBuilder payment(String reference) {
        return Payment.builder()
                .paymentReference(reference)
                .bookingId(++bookingSeq)
                .bookingReference("SBTEST")
                .amount(new BigDecimal("100.00"))
                .currency("USD");
    }

    // One violation per test - PostgreSQL aborts the transaction after the
    // first constraint failure (inventory JPA test lesson).

    @Test
    void paymentReferenceIsUnique() {
        paymentRepository.saveAndFlush(payment("PAY-2026-UNIQAA").build());

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(
                payment("PAY-2026-UNIQAA").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneBookingOnePayment() {
        Payment first = payment("PAY-2026-UNIQAB").build();
        paymentRepository.saveAndFlush(first);

        Payment duplicate = payment("PAY-2026-UNIQAC").bookingId(first.getBookingId()).build();
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyIsUniqueWhenPresent() {
        paymentRepository.saveAndFlush(payment("PAY-2026-UNIQAD").idempotencyKey("idem-1").build());

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(
                payment("PAY-2026-UNIQAE").idempotencyKey("idem-1").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullIdempotencyKeysMayCoexist() {
        paymentRepository.saveAndFlush(payment("PAY-2026-NULLAA").build());
        paymentRepository.saveAndFlush(payment("PAY-2026-NULLAB").build());

        assertThat(paymentRepository.count()).isEqualTo(2);
    }

    @Test
    void prePersistDefaultsStatusMethodAndAmounts() {
        Payment saved = paymentRepository.saveAndFlush(payment("PAY-2026-DEFAAA").build());

        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(saved.getCapturedAmount()).isEqualByComparingTo("0");
        assertThat(saved.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");
    }

    @Test
    void statusAndMethodAreStoredAsStrings() {
        Payment saved = paymentRepository.saveAndFlush(payment("PAY-2026-STRAAA").build());

        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "select status, method from payments where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(row[0]).isEqualTo("PENDING");
        assertThat(row[1]).isEqualTo("CARD");
    }

    @Test
    void versionIncrementsOnUpdate() {
        Payment saved = paymentRepository.saveAndFlush(payment("PAY-2026-VERAAA").build());
        Long initialVersion = saved.getVersion();

        saved.setStatus(PaymentStatus.AUTHORIZED);
        Payment updated = paymentRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
    }

    @Test
    void historyPersistsViaCascadeWithProvenanceInChangedAtOrder() {
        Payment saved = paymentRepository.saveAndFlush(payment("PAY-2026-HISAAA").build());

        saved.getHistory().add(PaymentHistory.builder()
                .payment(saved).historyType(PaymentHistoryType.AUTHORIZED)
                .actor("USER").source("API").correlationId("req-2").details("second")
                .changedAt(LocalDateTime.now().plusSeconds(1)).build());
        saved.getHistory().add(PaymentHistory.builder()
                .payment(saved).historyType(PaymentHistoryType.PAYMENT_CREATED)
                .actor("KAFKA").source("BOOKING_EVENT").correlationId("SBTEST").details("first")
                .changedAt(LocalDateTime.now().minusSeconds(1)).build());
        paymentRepository.saveAndFlush(saved);

        var entries = paymentHistoryRepository.findByPaymentIdOrderByChangedAtAsc(saved.getId());
        assertThat(entries).extracting(PaymentHistory::getDetails).containsExactly("first", "second");
        assertThat(entries.getFirst().getActor()).isEqualTo("KAFKA");
        assertThat(entries.getFirst().getSource()).isEqualTo("BOOKING_EVENT");
        assertThat(entries.getFirst().getCorrelationId()).isEqualTo("SBTEST");
    }

    @Test
    void findersByReferenceBookingAndIdempotencyKey() {
        Payment saved = paymentRepository.saveAndFlush(
                payment("PAY-2026-FNDAAA").idempotencyKey("idem-find").build());

        assertThat(paymentRepository.findByPaymentReference("PAY-2026-FNDAAA")).isPresent();
        assertThat(paymentRepository.findByBookingId(saved.getBookingId())).isPresent();
        assertThat(paymentRepository.existsByBookingId(saved.getBookingId())).isTrue();
        assertThat(paymentRepository.findByIdempotencyKey("idem-find")).isPresent();
        assertThat(paymentRepository.findByStatus(PaymentStatus.PENDING)).hasSize(1);
    }
}
