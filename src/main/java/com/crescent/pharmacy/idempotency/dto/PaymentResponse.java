package com.crescent.pharmacy.idempotency.dto;

import com.crescent.pharmacy.idempotency.entity.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response returned for every payment request – whether it is a new charge or a duplicate.
 *
 * Key fields for integrators:
 *   - duplicate: true  → this request was deduplicated; no new charge was made
 *   - duplicate: false → this was the first (and only) charge for this idempotency key
 *   - status: PENDING / COMPLETED / FAILED
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private UUID    paymentId;
    private String  idempotencyKey;
    private String  merchantId;
    private String  customerId;
    private BigDecimal amount;
    private String  currency;
    private PaymentStatus status;

    /** Processor reference (e.g. "YNO-3F7A2C1B") – present only when COMPLETED. */
    private String paymentReference;

    /** Human-readable decline / error reason – present only when FAILED. */
    private String failureReason;

    /** true if this request was identified as a duplicate and no new charge was made. */
    private boolean duplicate;

    /** Human-readable message about this response. */
    private String message;

    private Instant createdAt;
    private Instant completedAt;
    private Instant expiresAt;

    /** Marks this response as a duplicate and sets an explanatory message. */
    public PaymentResponse asDuplicate() {
        this.duplicate = true;
        this.message   = "Duplicate request – returning previously processed result. No new charge was made.";
        return this;
    }
}
