package com.crescent.pharmacy.idempotency.service;

import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import com.crescent.pharmacy.idempotency.entity.AuditEventType;
import com.crescent.pharmacy.idempotency.entity.PaymentAuditEntity;
import com.crescent.pharmacy.idempotency.repository.PaymentAuditRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records audit events and increments Micrometer counters.
 *
 * Uses {@code REQUIRES_NEW} so audit rows are committed independently of the
 * caller's transaction – audit data survives even if the payment rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final PaymentAuditRepository auditRepository;
    private final MeterRegistry meterRegistry;

    private Counter newPaymentsCounter;
    private Counter duplicatesBlockedCounter;
    private Counter paymentsCompletedCounter;
    private Counter paymentsFailedCounter;

    @PostConstruct
    public void initMetrics() {
        newPaymentsCounter        = counter("payments.new",               "New payment requests received");
        duplicatesBlockedCounter  = counter("payments.duplicates.blocked","Duplicate requests blocked");
        paymentsCompletedCounter  = counter("payments.completed",         "Payments successfully completed");
        paymentsFailedCounter     = counter("payments.failed",            "Payments declined or errored");
    }

    /** Log an audit event with full request context. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(String idempotencyKey, String merchantId,
                         AuditEventType eventType, PaymentRequest request) {
        try {
            PaymentAuditEntity entry = PaymentAuditEntity.builder()
                    .idempotencyKey(idempotencyKey)
                    .merchantId(merchantId)
                    .eventType(eventType)
                    .customerId(request != null ? request.getCustomerId() : null)
                    .amount(request    != null ? request.getAmount()     : null)
                    .currency(request  != null ? request.getCurrency()   : null)
                    .build();

            auditRepository.save(entry);
            updateMetric(eventType, idempotencyKey, merchantId);

        } catch (Exception ex) {
            // Audit failure must never affect the payment flow
            log.error("Failed to record audit event: key={}, event={}: {}",
                      idempotencyKey, eventType, ex.getMessage());
        }
    }

    /** Convenience overload when there is no request context (e.g. completion events). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(String idempotencyKey, String merchantId, AuditEventType eventType) {
        logEvent(idempotencyKey, merchantId, eventType, null);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void updateMetric(AuditEventType type, String key, String merchant) {
        switch (type) {
            case NEW_PAYMENT       -> newPaymentsCounter.increment();
            case DUPLICATE_BLOCKED -> {
                duplicatesBlockedCounter.increment();
                log.info("DUPLICATE_BLOCKED  key={} merchant={}", key, merchant);
            }
            case PAYMENT_COMPLETED -> paymentsCompletedCounter.increment();
            case PAYMENT_FAILED    -> paymentsFailedCounter.increment();
            default                -> { /* no metric for KEY_EXPIRED_REUSE */ }
        }
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(meterRegistry);
    }
}
