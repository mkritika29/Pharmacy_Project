package com.crescent.pharmacy.idempotency.entity;

public enum AuditEventType {
    /** First time this idempotency key has been seen – payment is being processed. */
    NEW_PAYMENT,
    /** A duplicate request arrived; the stored result was returned without reprocessing. */
    DUPLICATE_BLOCKED,
    /** The payment completed successfully. */
    PAYMENT_COMPLETED,
    /** The payment was declined or encountered a processor error. */
    PAYMENT_FAILED,
    /** An expired key was submitted; it was treated as a brand-new payment. */
    KEY_EXPIRED_REUSE
}
