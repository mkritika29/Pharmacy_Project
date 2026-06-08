package com.crescent.pharmacy.idempotency.entity;

public enum PaymentStatus {
    /** Payment is being processed by the upstream payment processor. */
    PENDING,
    /** Payment was successfully charged. */
    COMPLETED,
    /** Payment was declined by the processor or encountered a gateway error. */
    FAILED
}
