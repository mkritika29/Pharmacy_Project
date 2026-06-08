package com.crescent.pharmacy.idempotency.controller;

import com.crescent.pharmacy.idempotency.dto.PaymentMethodDetails;
import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import com.crescent.pharmacy.idempotency.dto.PaymentResponse;
import com.crescent.pharmacy.idempotency.service.PaymentIdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Developer/testing endpoints – NOT intended for production use.
 *
 *  POST /api/v1/test/generate   – Generate 100+ test payments (mix of unique and duplicates)
 *  POST /api/v1/test/concurrent – Send N identical requests simultaneously to prove
 *                                  race-condition safety
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestDataController {

    private static final String[] CURRENCIES  = {"EGP", "MAD", "TND"};
    private static final String[] PAY_METHODS = {"CARD", "MOBILE_WALLET"};
    private static final String[] CARD_BRANDS = {"VISA", "MASTERCARD", "AMEX"};
    private static final String[] WALLET_TYPES = {"VODAFONE_CASH", "FAWRY", "ORANGE_MONEY", "INSTAPAY"};
    private static final String[] DECLINE_REASONS_HINT = {"NORMAL", "HIGH_AMOUNT"};

    private final PaymentIdempotencyService idempotencyService;

    // ── POST /api/v1/test/generate ────────────────────────────────────────────

    /**
     * Generates {@code count} payment requests with:
     *  - 80 % unique idempotency keys
     *  - 20 % intentional duplicates (same key reused)
     *  - Amounts between $5 and $500 in EGP / MAD / TND
     *  - At least 5 keys used 3+ times
     *
     * @param count      total number of payment requests to send (default 100)
     * @param merchantId merchant to use for all requests (default MERCHANT_001)
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateTestData(
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(defaultValue = "MERCHANT_001") String merchantId
    ) {
        if (count < 1 || count > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "count must be 1–2000"));
        }

        Random rng = new Random();
        String[] customers = buildCustomerIds(20);

        // ── Build key pool ────────────────────────────────────────────────────
        int uniqueCount    = (int) (count * 0.80);
        int duplicateCount = count - uniqueCount;

        List<String> uniqueKeys = new ArrayList<>();
        for (int i = 0; i < uniqueCount; i++) {
            uniqueKeys.add(UUID.randomUUID().toString());
        }

        // Make sure at least 5 keys are used 3+ times
        List<String> highRetryKeys = uniqueKeys.subList(0, Math.min(5, uniqueKeys.size()));

        List<String> allKeys = new ArrayList<>(uniqueKeys);
        for (int i = 0; i < duplicateCount; i++) {
            // Bias towards the high-retry keys so we get 3+ retries on them
            if (i < 10 && !highRetryKeys.isEmpty()) {
                allKeys.add(highRetryKeys.get(i % highRetryKeys.size()));
            } else {
                allKeys.add(uniqueKeys.get(rng.nextInt(uniqueKeys.size())));
            }
        }
        Collections.shuffle(allKeys, rng);

        // ── Send all requests ─────────────────────────────────────────────────
        long newPayments = 0, duplicatesBlocked = 0, failedPayments = 0;

        for (String key : allKeys) {
            try {
                PaymentRequest request = buildRandomRequest(rng, customers);
                PaymentResponse response = idempotencyService.processPayment(key, merchantId, request);
                if (response.isDuplicate()) {
                    duplicatesBlocked++;
                } else if (response.getStatus() != null &&
                           "FAILED".equals(response.getStatus().name())) {
                    failedPayments++;
                } else {
                    newPayments++;
                }
            } catch (Exception ex) {
                log.warn("Test payment error for key={}: {}", key, ex.getMessage());
                failedPayments++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequestsSent",      allKeys.size());
        result.put("uniqueIdempotencyKeys",  uniqueCount);
        result.put("duplicateRequestsSent",  duplicateCount);
        result.put("newPaymentsProcessed",   newPayments);
        result.put("duplicatesBlocked",      duplicatesBlocked);
        result.put("failedPayments",         failedPayments);
        result.put("merchantId",             merchantId);
        result.put("highRetryKeyExamples",   highRetryKeys.subList(0, Math.min(3, highRetryKeys.size())));
        return ResponseEntity.ok(result);
    }

    // ── POST /api/v1/test/concurrent ─────────────────────────────────────────

    /**
     * Fires {@code concurrency} identical payment requests simultaneously using a thread pool
     * and a CountDownLatch to maximise overlap.
     *
     * The response shows whether exactly 1 charge was created despite concurrent hammering.
     * A passing result will have {@code "idempotencySuccessful": true}.
     *
     * @param concurrency number of simultaneous threads (default 10, max 50)
     * @param merchantId  merchant to use
     */
    @PostMapping("/concurrent")
    public ResponseEntity<Map<String, Object>> runConcurrentTest(
            @RequestParam(defaultValue = "10") int concurrency,
            @RequestParam(defaultValue = "MERCHANT_001") String merchantId
    ) throws InterruptedException {
        if (concurrency < 2 || concurrency > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "concurrency must be 2–50"));
        }

        String sharedKey = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder()
                .customerId("CONCURRENT_TEST_CUSTOMER")
                .amount(new BigDecimal("75.00"))
                .currency("EGP")
                .paymentMethod(PaymentMethodDetails.builder()
                        .type("CARD").cardLastFour("4242").cardBrand("VISA").build())
                .description("Concurrent idempotency test")
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<PaymentResponse>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                startGun.await(); // All threads wait here until startGun fires
                return idempotencyService.processPayment(sharedKey, merchantId, request);
            }));
        }

        // Release all threads simultaneously
        startGun.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);

        // ── Collect results ───────────────────────────────────────────────────
        List<PaymentResponse> responses = new ArrayList<>();
        int errors = 0;
        for (Future<PaymentResponse> future : futures) {
            try {
                responses.add(future.get());
            } catch (ExecutionException ex) {
                log.error("Concurrent request threw: {}", ex.getCause().getMessage());
                errors++;
            }
        }

        long newCharges         = responses.stream().filter(r -> !r.isDuplicate()).count();
        long duplicatesBlocked  = responses.stream().filter(PaymentResponse::isDuplicate).count();
        Set<String> uniquePaymentIds = responses.stream()
                .filter(r -> r.getPaymentId() != null)
                .map(r -> r.getPaymentId().toString())
                .collect(Collectors.toSet());

        boolean idempotencyOk = uniquePaymentIds.size() <= 1 && newCharges <= 1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sharedIdempotencyKey",   sharedKey);
        result.put("concurrentRequests",     concurrency);
        result.put("threadsFinished",        finished);
        result.put("newChargesCreated",      newCharges);
        result.put("duplicatesBlocked",      duplicatesBlocked);
        result.put("errors",                 errors);
        result.put("uniquePaymentIds",       uniquePaymentIds.size());
        result.put("idempotencySuccessful",  idempotencyOk);
        result.put("verdict", idempotencyOk
                ? "✓ PASS – only 1 charge created despite " + concurrency + " concurrent requests"
                : "✗ FAIL – " + uniquePaymentIds.size() + " distinct charges detected!");

        return ResponseEntity.ok(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PaymentRequest buildRandomRequest(Random rng, String[] customers) {
        String method = PAY_METHODS[rng.nextInt(PAY_METHODS.length)];
        PaymentMethodDetails pmd = "CARD".equals(method)
                ? PaymentMethodDetails.builder()
                    .type("CARD")
                    .cardLastFour(String.format("%04d", rng.nextInt(10000)))
                    .cardBrand(CARD_BRANDS[rng.nextInt(CARD_BRANDS.length)])
                    .build()
                : PaymentMethodDetails.builder()
                    .type("MOBILE_WALLET")
                    .walletType(WALLET_TYPES[rng.nextInt(WALLET_TYPES.length)])
                    .walletPhoneNumber("010" + String.format("%08d", rng.nextInt(100_000_000)))
                    .build();

        return PaymentRequest.builder()
                .customerId(customers[rng.nextInt(customers.length)])
                .amount(randomAmount(rng))
                .currency(CURRENCIES[rng.nextInt(CURRENCIES.length)])
                .paymentMethod(pmd)
                .description("Prescription pickup – test")
                .build();
    }

    private BigDecimal randomAmount(Random rng) {
        // Range: 5.00 to 500.00
        int cents = 500 + rng.nextInt(49_501);
        return new BigDecimal(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private String[] buildCustomerIds(int count) {
        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            ids[i] = "CUST_" + String.format("%04d", i + 1);
        }
        return ids;
    }
}
