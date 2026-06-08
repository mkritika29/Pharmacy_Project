package com.crescent.pharmacy.idempotency.service;

import com.crescent.pharmacy.idempotency.config.AppProperties;
import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import com.crescent.pharmacy.idempotency.dto.PaymentResponse;
import com.crescent.pharmacy.idempotency.entity.AuditEventType;
import com.crescent.pharmacy.idempotency.entity.IdempotencyKeyEntity;
import com.crescent.pharmacy.idempotency.entity.PaymentStatus;
import com.crescent.pharmacy.idempotency.exception.IdempotencyKeyNotFoundException;
import com.crescent.pharmacy.idempotency.exception.PaymentProcessingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Core orchestrator for idempotent payment processing.
 *
 * ── Payment flow ──────────────────────────────────────────────────────────────
 *
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │  Request arrives with X-Idempotency-Key                             │
 *  │                                                                     │
 *  │  1. Check Redis cache  ──► HIT  → return cached result (duplicate)  │
 *  │         │                                                            │
 *  │         MISS                                                         │
 *  │         │                                                            │
 *  │  2. claimKey() in DB (REQUIRES_NEW transaction)                     │
 *  │       ├── INSERT succeeds → NEW CLAIM → process payment (step 3)   │
 *  │       └── INSERT fails (constraint / race) → EXISTING CLAIM        │
 *  │                    │                                                │
 *  │                    ├── status = COMPLETED/FAILED → return result    │
 *  │                    └── status = PENDING → poll until complete       │
 *  │                                                                     │
 *  │  3. Call MockPaymentProcessor                                       │
 *  │  4. markCompleted() in DB                                           │
 *  │  5. Store result in Redis                                            │
 *  │  6. Return PaymentResponse (duplicate=false)                        │
 *  └─────────────────────────────────────────────────────────────────────┘
 *
 * Concurrency safety: no distributed lock needed.  The unique DB constraint on
 * (idempotency_key, merchant_id) ensures exactly one thread creates the PENDING
 * record.  All others see the constraint violation and fall into the "existing
 * claim" branch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIdempotencyService {

    private final IdempotencyKeyPersistenceService persistenceService;
    private final RedisIdempotencyCache            redisCache;
    private final MockPaymentProcessor             mockProcessor;
    private final AuditService                     auditService;
    private final ObjectMapper                     objectMapper;
    private final AppProperties                    properties;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process a payment with idempotency guarantee.
     *
     * @param idempotencyKey unique key from the X-Idempotency-Key header
     * @param merchantId     pharmacy branch identifier
     * @param request        payment details
     * @return the payment result (same result for every duplicate call)
     */
    public PaymentResponse processPayment(String idempotencyKey,
                                          String merchantId,
                                          PaymentRequest request) {
        MDC.put("idempotencyKey", idempotencyKey);
        MDC.put("merchantId", merchantId);
        try {
            log.info("Payment request received: key={}, merchant={}, amount={} {}",
                     idempotencyKey, merchantId, request.getAmount(), request.getCurrency());

            // ── Step 1: Fast path via Redis cache ─────────────────────────────
            Optional<PaymentResponse> cached = redisCache.get(merchantId, idempotencyKey);
            if (cached.isPresent()) {
                log.info("FAST-PATH cache hit: key={}, merchant={}", idempotencyKey, merchantId);
                auditService.logEvent(idempotencyKey, merchantId, AuditEventType.DUPLICATE_BLOCKED, request);
                return cached.get().asDuplicate();
            }

            // ── Step 2: Atomic DB claim ───────────────────────────────────────
            IdempotencyClaimResult claim = persistenceService.claimKey(idempotencyKey, merchantId, request);

            if (claim.isNewClaim()) {
                return processNewPayment(claim.getEntity(), request, idempotencyKey, merchantId);
            } else {
                return handleDuplicateRequest(claim.getEntity(), idempotencyKey, merchantId, request);
            }

        } finally {
            MDC.remove("idempotencyKey");
            MDC.remove("merchantId");
        }
    }

    /**
     * Query the status of a payment by its idempotency key.
     * Checks Redis first, then falls back to PostgreSQL.
     */
    public PaymentResponse getPaymentStatus(String idempotencyKey, String merchantId) {
        Optional<PaymentResponse> cached = redisCache.get(merchantId, idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        return persistenceService.findByKeyAndMerchant(idempotencyKey, merchantId)
                .map(entity -> buildResponse(entity, false))
                .orElseThrow(() -> new IdempotencyKeyNotFoundException(
                        "No payment found for key: " + idempotencyKey + ", merchant: " + merchantId));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private PaymentResponse processNewPayment(IdempotencyKeyEntity record,
                                              PaymentRequest request,
                                              String key,
                                              String merchantId) {
        auditService.logEvent(key, merchantId, AuditEventType.NEW_PAYMENT, request);
        try {
            MockPaymentResult result = mockProcessor.process(request);

            IdempotencyKeyEntity updated =
                    persistenceService.markCompleted(record, result, toJson(result));

            AuditEventType completionEvent = result.isSuccess()
                    ? AuditEventType.PAYMENT_COMPLETED
                    : AuditEventType.PAYMENT_FAILED;
            auditService.logEvent(key, merchantId, completionEvent);

            PaymentResponse response = buildResponse(updated, false);
            redisCache.store(merchantId, key, response, updated.getExpiresAt());

            log.info("Payment {}: key={}, merchant={}, ref={}",
                     result.isSuccess() ? "COMPLETED" : "FAILED",
                     key, merchantId, result.getPaymentReference());
            return response;

        } catch (Exception ex) {
            log.error("Payment processing error: key={}, merchant={}: {}", key, merchantId, ex.getMessage(), ex);
            persistenceService.markFailed(record, "Processing error: " + ex.getMessage());
            auditService.logEvent(key, merchantId, AuditEventType.PAYMENT_FAILED);
            throw new PaymentProcessingException("Payment processing failed: " + ex.getMessage(), ex);
        }
    }

    private PaymentResponse handleDuplicateRequest(IdempotencyKeyEntity record,
                                                   String key,
                                                   String merchantId,
                                                   PaymentRequest request) {
        log.info("DUPLICATE detected: key={}, merchant={}, status={}",
                 key, merchantId, record.getStatus());
        auditService.logEvent(key, merchantId, AuditEventType.DUPLICATE_BLOCKED, request);

        // If a concurrent first-request is still being processed, wait for it
        if (record.getStatus() == PaymentStatus.PENDING) {
            record = waitForCompletion(key, merchantId);
        }

        PaymentResponse response = buildResponse(record, true);

        // Warm the cache for future calls
        if (record.getStatus() != PaymentStatus.PENDING) {
            redisCache.store(merchantId, key, response, record.getExpiresAt());
        }
        return response;
    }

    /**
     * Polls PostgreSQL with exponential back-off until the PENDING record
     * transitions to COMPLETED or FAILED (or we time out).
     */
    private IdempotencyKeyEntity waitForCompletion(String key, String merchantId) {
        AppProperties.Idempotency cfg = properties.getIdempotency();
        long waitMs = cfg.getWaitIntervalMs();

        for (int attempt = 1; attempt <= cfg.getMaxWaitAttempts(); attempt++) {
            sleep(waitMs);
            waitMs = Math.min(waitMs * 2, 1_000); // Exponential back-off, cap at 1 s

            Optional<IdempotencyKeyEntity> updated =
                    persistenceService.findByKeyAndMerchant(key, merchantId);
            if (updated.isPresent() && updated.get().getStatus() != PaymentStatus.PENDING) {
                log.debug("PENDING resolved after {} poll(s): key={}", attempt, key);
                return updated.get();
            }
        }

        log.warn("Timed out waiting for PENDING payment to complete: key={}, merchant={}", key, merchantId);
        // Return whatever state we have – caller will see PENDING in the response
        return persistenceService.findByKeyAndMerchant(key, merchantId)
                .orElseThrow(() -> new PaymentProcessingException(
                        "Payment record disappeared while waiting: key=" + key));
    }

    private PaymentResponse buildResponse(IdempotencyKeyEntity entity, boolean isDuplicate) {
        PaymentResponse response = PaymentResponse.builder()
                .paymentId(entity.getId())
                .idempotencyKey(entity.getIdempotencyKey())
                .merchantId(entity.getMerchantId())
                .customerId(entity.getCustomerId())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .status(entity.getStatus())
                .paymentReference(entity.getPaymentReference())
                .failureReason(entity.getFailureReason())
                .duplicate(isDuplicate)
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .expiresAt(entity.getExpiresAt())
                .build();

        if (isDuplicate) {
            response.setMessage("Duplicate request – returning previously processed result. No new charge was made.");
        }
        return response;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PaymentProcessingException("Interrupted while waiting for payment completion");
        }
    }
}
