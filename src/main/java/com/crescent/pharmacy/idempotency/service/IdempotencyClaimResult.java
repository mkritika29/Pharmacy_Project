package com.crescent.pharmacy.idempotency.service;

import com.crescent.pharmacy.idempotency.entity.IdempotencyKeyEntity;
import lombok.Getter;

/**
 * Value object returned by {@link IdempotencyKeyPersistenceService#claimKey}.
 *
 * {@code newClaim = true}  → the calling thread won the race; it must process the payment.
 * {@code newClaim = false} → another thread already owns this key; caller should wait/return.
 */
@Getter
public final class IdempotencyClaimResult {

    private final IdempotencyKeyEntity entity;
    private final boolean newClaim;

    private IdempotencyClaimResult(IdempotencyKeyEntity entity, boolean newClaim) {
        this.entity   = entity;
        this.newClaim = newClaim;
    }

    public static IdempotencyClaimResult newClaim(IdempotencyKeyEntity entity) {
        return new IdempotencyClaimResult(entity, true);
    }

    public static IdempotencyClaimResult existingClaim(IdempotencyKeyEntity entity) {
        return new IdempotencyClaimResult(entity, false);
    }
}
