package com.crescent.pharmacy.idempotency.service;

import com.crescent.pharmacy.idempotency.config.AppProperties;
import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

/**
 * Simulates the Yuno payment processor (or any real payment gateway).
 *
 * Outcome distribution (configurable via application.yml):
 *   - COMPLETED  : 80 %  (default)
 *   - DECLINED   : 15 %
 *   - GATEWAY ERROR: 5 %
 *
 * Processing delay of 50–200 ms is injected to simulate real network latency and
 * make concurrent-duplicate scenarios easier to observe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockPaymentProcessor {

    private final AppProperties properties;
    private final Random random = new Random();

    public MockPaymentResult process(PaymentRequest request) {
        simulateNetworkDelay();

        double roll = random.nextDouble();
        AppProperties.MockProcessor cfg = properties.getMockProcessor();

        if (roll < cfg.getSuccessRate()) {
            String ref = "YNO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.debug("Mock processor: APPROVED ref={}, amount={} {}",
                      ref, request.getAmount(), request.getCurrency());
            return MockPaymentResult.builder()
                    .success(true)
                    .status("COMPLETED")
                    .paymentReference(ref)
                    .processorResponse("APPROVED")
                    .build();

        } else if (roll < cfg.getSuccessRate() + cfg.getDeclineRate()) {
            String reason = declineReason(request.getAmount());
            log.debug("Mock processor: DECLINED reason={}, amount={} {}",
                      reason, request.getAmount(), request.getCurrency());
            return MockPaymentResult.builder()
                    .success(false)
                    .status("DECLINED")
                    .failureReason(reason)
                    .processorResponse("DECLINED")
                    .build();

        } else {
            log.warn("Mock processor: GATEWAY_ERROR amount={} {}", request.getAmount(), request.getCurrency());
            return MockPaymentResult.builder()
                    .success(false)
                    .status("ERROR")
                    .failureReason("Upstream payment processor temporarily unavailable")
                    .processorResponse("GATEWAY_ERROR")
                    .build();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void simulateNetworkDelay() {
        AppProperties.MockProcessor cfg = properties.getMockProcessor();
        int range = cfg.getProcessingDelayMaxMs() - cfg.getProcessingDelayMinMs();
        int delay = cfg.getProcessingDelayMinMs() + (range > 0 ? random.nextInt(range) : 0);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String declineReason(BigDecimal amount) {
        // Higher amounts skew towards insufficient-funds
        if (amount.compareTo(new BigDecimal("400")) > 0) {
            return "INSUFFICIENT_FUNDS";
        }
        String[] reasons = {
            "INSUFFICIENT_FUNDS",
            "CARD_EXPIRED",
            "TRANSACTION_LIMIT_EXCEEDED",
            "DO_NOT_HONOR",
            "INVALID_CVV"
        };
        return reasons[random.nextInt(reasons.length)];
    }
}
