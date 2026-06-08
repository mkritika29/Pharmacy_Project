package com.crescent.pharmacy.idempotency.controller;

import com.crescent.pharmacy.idempotency.dto.StatsResponse;
import com.crescent.pharmacy.idempotency.entity.AuditEventType;
import com.crescent.pharmacy.idempotency.entity.PaymentAuditEntity;
import com.crescent.pharmacy.idempotency.entity.PaymentStatus;
import com.crescent.pharmacy.idempotency.repository.IdempotencyKeyRepository;
import com.crescent.pharmacy.idempotency.repository.PaymentAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Observability endpoints – duplicate detection metrics and audit log queries.
 *
 *  GET /api/v1/stats               – Aggregate deduplication statistics
 *  GET /api/v1/stats/audit         – Recent audit log entries (filterable by merchant/hours)
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final IdempotencyKeyRepository keyRepository;
    private final PaymentAuditRepository   auditRepository;

    // ── GET /api/v1/stats ─────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<StatsResponse> getStats() {

        long completed         = keyRepository.countByStatus(PaymentStatus.COMPLETED);
        long failed            = keyRepository.countByStatus(PaymentStatus.FAILED);
        long pending           = keyRepository.countByStatus(PaymentStatus.PENDING);
        long totalPayments     = completed + failed;
        long totalDuplicates   = auditRepository.countByEventType(AuditEventType.DUPLICATE_BLOCKED);
        long dupsLastHour      = auditRepository.countByEventTypeAndCreatedAtAfter(
                                         AuditEventType.DUPLICATE_BLOCKED,
                                         Instant.now().minus(1, ChronoUnit.HOURS));

        double dupRatePct = (totalPayments + totalDuplicates) > 0
                ? (double) totalDuplicates / (totalPayments + totalDuplicates) * 100
                : 0.0;

        // ── Per-merchant stats ────────────────────────────────────────────────
        Map<String, Long> dupsByMerchant = auditRepository
                .countByEventTypeGroupedByMerchant(AuditEventType.DUPLICATE_BLOCKED)
                .stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));

        List<StatsResponse.MerchantStats> merchantStats = keyRepository.countGroupedByMerchant()
                .stream()
                .map(row -> StatsResponse.MerchantStats.builder()
                        .merchantId((String) row[0])
                        .totalPayments((Long) row[1])
                        .totalDuplicates(dupsByMerchant.getOrDefault((String) row[0], 0L))
                        .totalFailed(keyRepository.countByMerchantIdAndStatus(
                                (String) row[0], PaymentStatus.FAILED))
                        .build())
                .toList();

        // ── Top retried keys (up to 10) ───────────────────────────────────────
        List<StatsResponse.TopRetriedKey> topKeys = auditRepository
                .findTopRetriedKeys(AuditEventType.DUPLICATE_BLOCKED, PageRequest.of(0, 10))
                .stream()
                .map(row -> StatsResponse.TopRetriedKey.builder()
                        .idempotencyKey((String) row[0])
                        .merchantId((String) row[1])
                        .duplicateAttemptCount((Long) row[2])
                        .firstAttemptAt((Instant) row[3])
                        .lastAttemptAt((Instant) row[4])
                        .build())
                .toList();

        return ResponseEntity.ok(StatsResponse.builder()
                .reportGeneratedAt(Instant.now())
                .totalPaymentsProcessed(totalPayments)
                .totalDuplicatesBlocked(totalDuplicates)
                .totalFailed(failed)
                .totalPending(pending)
                .duplicatesBlockedLastHour(dupsLastHour)
                .duplicateRatePct(Math.round(dupRatePct * 100.0) / 100.0)
                .merchantStats(merchantStats)
                .topRetriedKeys(topKeys)
                .build());
    }

    // ── GET /api/v1/stats/audit ───────────────────────────────────────────────

    /**
     * Returns recent audit log entries.
     *
     * @param merchantId filter by merchant (default: "default")
     * @param hours      look-back window in hours (default: 1)
     */
    @GetMapping("/audit")
    public ResponseEntity<List<PaymentAuditEntity>> getAuditLog(
            @RequestParam(defaultValue = "default") String merchantId,
            @RequestParam(defaultValue = "1") int hours
    ) {
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<PaymentAuditEntity> entries = auditRepository
                .findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        merchantId, from, Instant.now());
        return ResponseEntity.ok(entries);
    }
}
