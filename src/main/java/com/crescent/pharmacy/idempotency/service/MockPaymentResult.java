package com.crescent.pharmacy.idempotency.service;

import lombok.*;

/**
 * Value object representing the outcome returned by the mock payment processor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockPaymentResult {

    /** true if the payment was approved. */
    private boolean success;

    /** COMPLETED | DECLINED | ERROR */
    private String status;

    /** Yuno-style transaction reference, present when success=true. */
    private String paymentReference;

    /** Human-readable reason for decline/error, present when success=false. */
    private String failureReason;

    /** Raw response from the (simulated) upstream processor. */
    private String processorResponse;
}
