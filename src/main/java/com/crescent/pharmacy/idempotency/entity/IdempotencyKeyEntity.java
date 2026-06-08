package com.crescent.pharmacy.idempotency.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a single payment intent, keyed by (idempotency_key, merchant_id).
 *
 * Lifecycle:
 *   1. Created with status=PENDING when a payment request is first received.
 *   2. Updated to COMPLETED or FAILED once the mock processor responds.
 *   3. Duplicate requests with the same key hit the Redis cache or read this row.
 *
 * The UNIQUE constraint on (idempotency_key, merchant_id) is the atomic guard
 * that prevents concurrent requests from creating multiple charges.
 */
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_idempotency_key_merchant",
        columnNames = {"idempotency_key", "merchant_id"}
    )
)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "merchant_id", nullable = false, length = 255)
    private String merchantId;

    @Column(name = "customer_id", nullable = false, length = 255)
    private String customerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Returns true if this key is past its expiration window. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
