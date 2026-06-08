package com.crescent.pharmacy.idempotency.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate statistics for the deduplication service.
 * Returned by GET /api/v1/stats
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatsResponse {

    private Instant reportGeneratedAt;

    /** Distinct payment intents processed (COMPLETED + FAILED). */
    private long totalPaymentsProcessed;

    /** Duplicate requests that were intercepted and not forwarded to the processor. */
    private long totalDuplicatesBlocked;

    private long totalFailed;
    private long totalPending;

    /** Duplicates blocked in the last 60 minutes. */
    private long duplicatesBlockedLastHour;

    /** duplicatesBlocked / (totalPayments + duplicatesBlocked) × 100 */
    private double duplicateRatePct;

    private List<MerchantStats> merchantStats;

    /** Top 10 most-retried idempotency keys (highest duplicate attempt count). */
    private List<TopRetriedKey> topRetriedKeys;

    // ── Nested DTOs ────────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MerchantStats {
        private String merchantId;
        private long   totalPayments;
        private long   totalDuplicates;
        private long   totalFailed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopRetriedKey {
        private String  idempotencyKey;
        private String  merchantId;
        private long    duplicateAttemptCount;
        private Instant firstAttemptAt;
        private Instant lastAttemptAt;
    }
}
