package com.crescent.pharmacy.idempotency.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed configuration properties for the idempotency service.
 * Values are loaded from application.yml under the "app" prefix.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Idempotency idempotency = new Idempotency();
    private MockProcessor mockProcessor = new MockProcessor();

    @Data
    public static class Idempotency {
        /** How long (hours) an idempotency key stays active before expiry. */
        private long keyExpirationHours = 24;

        /** Seconds a PENDING record may live before being considered stale. */
        private long pendingTimeoutSeconds = 300;

        /** Max poll attempts while waiting for a concurrent PENDING payment. */
        private int maxWaitAttempts = 15;

        /** Initial wait interval (ms) between polls; doubles on each retry. */
        private long waitIntervalMs = 200;
    }

    @Data
    public static class MockProcessor {
        private double successRate = 0.80;
        private double declineRate = 0.15;
        private double errorRate   = 0.05;
        private int processingDelayMinMs = 50;
        private int processingDelayMaxMs = 200;
    }
}
