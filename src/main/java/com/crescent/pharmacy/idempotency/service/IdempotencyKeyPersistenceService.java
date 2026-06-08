package com.crescent.pharmacy.idempotency.service;

import com.crescent.pharmacy.idempotency.config.AppProperties;
import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import com.crescent.pharmacy.idempotency.entity.IdempotencyKeyEntity;
import com.crescent.pharmacy.idempotency.entity.PaymentStatus;
import com.crescent.pharmacy.idempotency.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Handles all database operations for idempotency key management.
 *
 * ── Concurrency safety strategy ──────────────────────────────────────────────
 * {@link #claimKey} runs in its own {@code REQUIRES_NEW} transaction.
 * This means:
 *
 *   1. The INSERT is committed immediately, before the caller processes the payment.
 *   2. Any concurrent duplicate request that arrives while the payment is in-flight
 *      will see the PENDING row (rather than no row at all).
 *   3. If two requests arrive in the exact same millisecond, exactly ONE will
 *      succeed at the INSERT.  The other hits the UNIQUE constraint and receives a
 *      {@link DataIntegrityViolationException}.  That exception is caught here and
 *      turned into an {@code existingClaim} result – so the duplicate thread
 *      gracefully falls back to returning the already-stored result.
 *
 * This approach uses the database itself (PostgreSQL's MVCC + constraints) as the
 * distributed lock, eliminating the need for a separate Redis lock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyKeyPersistenceService {

    private final IdempotencyKeyRepository repository;
    private final AppProperties properties;

    /**
     * Atomically claim an idempotency key for processing.
     *
     * @return {@link IdempotencyClaimResult#newClaim} if the caller now owns
     *         the processing; {@link IdempotencyClaimResult#existingClaim}
     *         if the key already exists (duplicate or concurrent request).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyClaimResult claimKey(String key, String merchantId, PaymentRequest request) {

        Optional<IdempotencyKeyEntity> existing = repository.findByIdempotencyKeyAndMerchantId(key, merchantId);

        if (existing.isPresent()) {
            IdempotencyKeyEntity entity = existing.get();

            if (entity.isExpired()) {
                // Key has passed its TTL – delete it and allow re-use
                log.info("Idempotency key expired; allowing re-use: key={}, merchant={}", key, merchantId);
                repository.delete(entity);
                repository.flush();
                // Fall through to create a fresh PENDING record
            } else {
                // Active record exists – this is a duplicate request
                return IdempotencyClaimResult.existingClaim(entity);
            }
        }

        // Create a new PENDING record in this transaction
        Instant now = Instant.now();
        IdempotencyKeyEntity newEntity = IdempotencyKeyEntity.builder()
                .idempotencyKey(key)
                .merchantId(merchantId)
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethod(request.getPaymentMethod() != null
                               ? request.getPaymentMethod().getType() : null)
                .status(PaymentStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plus(properties.getIdempotency().getKeyExpirationHours(), ChronoUnit.HOURS))
                .build();

        try {
            IdempotencyKeyEntity saved = repository.saveAndFlush(newEntity);
            log.debug("Claimed new idempotency key: key={}, merchant={}", key, merchantId);
            return IdempotencyClaimResult.newClaim(saved);

        } catch (DataIntegrityViolationException ex) {
            /*
             * Race condition: two identical requests arrived at the same instant.
             * The other thread beat us to the INSERT.  Fetch what it created.
             */
            log.debug("Race condition on key={}, merchant={} – fetching winner's record", key, merchantId);
            IdempotencyKeyEntity winner = repository.findByIdempotencyKeyAndMerchantId(key, merchantId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Constraint violation but no entity found for key: " + key));
            return IdempotencyClaimResult.existingClaim(winner);
        }
    }

    /** Mark a PENDING record as COMPLETED or FAILED based on the processor result. */
    @Transactional
    public IdempotencyKeyEntity markCompleted(IdempotencyKeyEntity entity,
                                              MockPaymentResult result,
                                              String responseJson) {
        entity.setStatus(result.isSuccess() ? PaymentStatus.COMPLETED : PaymentStatus.FAILED);
        entity.setPaymentReference(result.getPaymentReference());
        entity.setFailureReason(result.getFailureReason());
        entity.setResponsePayload(responseJson);
        entity.setCompletedAt(Instant.now());
        return repository.save(entity);
    }

    /** Mark a PENDING record as FAILED (used when the processor call itself throws). */
    @Transactional
    public IdempotencyKeyEntity markFailed(IdempotencyKeyEntity entity, String reason) {
        entity.setStatus(PaymentStatus.FAILED);
        entity.setFailureReason(reason);
        entity.setCompletedAt(Instant.now());
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyKeyEntity> findByKeyAndMerchant(String key, String merchantId) {
        return repository.findByIdempotencyKeyAndMerchantId(key, merchantId);
    }
}
