package com.crescent.pharmacy.idempotency.repository;

import com.crescent.pharmacy.idempotency.entity.IdempotencyKeyEntity;
import com.crescent.pharmacy.idempotency.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    /** Lookup regardless of expiry – used for duplicate detection. */
    Optional<IdempotencyKeyEntity> findByIdempotencyKeyAndMerchantId(
            String idempotencyKey, String merchantId);

    long countByStatus(PaymentStatus status);

    long countByMerchantIdAndStatus(String merchantId, PaymentStatus status);

    /** Returns [merchantId, count] pairs for the stats dashboard. */
    @Query("SELECT e.merchantId, COUNT(e) FROM IdempotencyKeyEntity e GROUP BY e.merchantId ORDER BY COUNT(e) DESC")
    List<Object[]> countGroupedByMerchant();

    /** Used by the cleanup job to find stale PENDING records. */
    @Query("SELECT e FROM IdempotencyKeyEntity e WHERE e.status = 'PENDING' " +
           "AND e.createdAt < :threshold")
    List<IdempotencyKeyEntity> findStalePendingRecords(
            @Param("threshold") java.time.Instant threshold);
}
