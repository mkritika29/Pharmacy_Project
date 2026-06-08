package com.crescent.pharmacy.idempotency.repository;

import com.crescent.pharmacy.idempotency.entity.AuditEventType;
import com.crescent.pharmacy.idempotency.entity.PaymentAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentAuditRepository extends JpaRepository<PaymentAuditEntity, UUID> {

    /** Full audit trail for one key/merchant pair, chronological. */
    List<PaymentAuditEntity> findByIdempotencyKeyAndMerchantIdOrderByCreatedAtAsc(
            String idempotencyKey, String merchantId);

    long countByEventType(AuditEventType eventType);

    long countByEventTypeAndCreatedAtAfter(AuditEventType eventType, Instant since);

    /**
     * Returns [idempotencyKey, merchantId, count, firstAt, lastAt] for the
     * top most-retried keys (by DUPLICATE_BLOCKED events).
     */
    @Query("""
        SELECT a.idempotencyKey, a.merchantId, COUNT(a), MIN(a.createdAt), MAX(a.createdAt)
        FROM PaymentAuditEntity a
        WHERE a.eventType = :eventType
        GROUP BY a.idempotencyKey, a.merchantId
        ORDER BY COUNT(a) DESC
        """)
    List<Object[]> findTopRetriedKeys(@Param("eventType") AuditEventType eventType, Pageable pageable);

    /** Returns [merchantId, count] of duplicate events per merchant. */
    @Query("""
        SELECT a.merchantId, COUNT(a)
        FROM PaymentAuditEntity a
        WHERE a.eventType = :eventType
        GROUP BY a.merchantId
        """)
    List<Object[]> countByEventTypeGroupedByMerchant(@Param("eventType") AuditEventType eventType);

    /** Recent audit entries for a specific merchant, newest first. */
    List<PaymentAuditEntity> findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String merchantId, Instant from, Instant to);
}
