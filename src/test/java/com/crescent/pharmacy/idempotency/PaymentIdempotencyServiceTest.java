package com.crescent.pharmacy.idempotency;

import com.crescent.pharmacy.idempotency.config.AppProperties;
import com.crescent.pharmacy.idempotency.dto.PaymentMethodDetails;
import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import com.crescent.pharmacy.idempotency.dto.PaymentResponse;
import com.crescent.pharmacy.idempotency.entity.IdempotencyKeyEntity;
import com.crescent.pharmacy.idempotency.entity.PaymentStatus;
import com.crescent.pharmacy.idempotency.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentIdempotencyService}.
 * All dependencies are mocked – no Spring context, no database, no Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentIdempotencyService – Unit Tests")
class PaymentIdempotencyServiceTest {

    @Mock private IdempotencyKeyPersistenceService persistenceService;
    @Mock private RedisIdempotencyCache            redisCache;
    @Mock private MockPaymentProcessor             mockProcessor;
    @Mock private AuditService                     auditService;

    private PaymentIdempotencyService service;

    private static final String MERCHANT_ID = "PHARMACY_001";
    private static final String KEY         = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getIdempotency().setMaxWaitAttempts(3);
        props.getIdempotency().setWaitIntervalMs(10);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new PaymentIdempotencyService(
                persistenceService, redisCache, mockProcessor, auditService, mapper, props);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test: new payment is processed once and result is cached
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("New payment request → processor called exactly once, result cached")
    void newPayment_processedOnce_resultCached() {
        PaymentRequest request  = buildRequest("50.00", "EGP");
        IdempotencyKeyEntity pendingEntity  = buildEntity(PaymentStatus.PENDING);
        IdempotencyKeyEntity completedEntity = pendingEntity.toBuilder()
                .status(PaymentStatus.COMPLETED)
                .paymentReference("YNO-ABC123")
                .completedAt(Instant.now())
                .build();

        MockPaymentResult mockResult = MockPaymentResult.builder()
                .success(true).status("COMPLETED").paymentReference("YNO-ABC123").build();

        when(redisCache.get(MERCHANT_ID, KEY)).thenReturn(Optional.empty());
        when(persistenceService.claimKey(KEY, MERCHANT_ID, request))
                .thenReturn(IdempotencyClaimResult.newClaim(pendingEntity));
        when(mockProcessor.process(request)).thenReturn(mockResult);
        when(persistenceService.markCompleted(any(), any(), any())).thenReturn(completedEntity);

        PaymentResponse response = service.processPayment(KEY, MERCHANT_ID, request);

        assertThat(response.isDuplicate()).isFalse();
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.getPaymentReference()).isEqualTo("YNO-ABC123");

        verify(mockProcessor, times(1)).process(request);
        verify(redisCache, times(1)).store(eq(MERCHANT_ID), eq(KEY), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test: duplicate request served from Redis cache
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate request – Redis cache hit → processor NEVER called")
    void duplicateRequest_redisCacheHit_processorNotCalled() {
        PaymentRequest  request        = buildRequest("75.00", "MAD");
        PaymentResponse cachedResponse = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .idempotencyKey(KEY)
                .merchantId(MERCHANT_ID)
                .status(PaymentStatus.COMPLETED)
                .paymentReference("YNO-CACHED")
                .build();

        when(redisCache.get(MERCHANT_ID, KEY)).thenReturn(Optional.of(cachedResponse));

        PaymentResponse response = service.processPayment(KEY, MERCHANT_ID, request);

        assertThat(response.isDuplicate()).isTrue();
        assertThat(response.getPaymentReference()).isEqualTo("YNO-CACHED");

        // Processor must NOT be called for a cached duplicate
        verify(mockProcessor, never()).process(any());
        // DB claim must NOT be attempted for a cached duplicate
        verify(persistenceService, never()).claimKey(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test: duplicate request – DB existing claim (no Redis hit)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate request – DB existing claim → processor NEVER called")
    void duplicateRequest_dbExistingClaim_processorNotCalled() {
        PaymentRequest       request        = buildRequest("100.00", "TND");
        IdempotencyKeyEntity completedEntity = buildEntity(PaymentStatus.COMPLETED)
                .toBuilder().paymentReference("YNO-DB-HIT").completedAt(Instant.now()).build();

        when(redisCache.get(MERCHANT_ID, KEY)).thenReturn(Optional.empty());
        when(persistenceService.claimKey(KEY, MERCHANT_ID, request))
                .thenReturn(IdempotencyClaimResult.existingClaim(completedEntity));

        PaymentResponse response = service.processPayment(KEY, MERCHANT_ID, request);

        assertThat(response.isDuplicate()).isTrue();
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        verify(mockProcessor, never()).process(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test: five identical calls → exactly one charge
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Five identical requests → exactly 1 charge created")
    void fiveIdenticalRequests_exactlyOneCharge() {
        PaymentRequest       request        = buildRequest("45.00", "EGP");
        IdempotencyKeyEntity pending        = buildEntity(PaymentStatus.PENDING);
        IdempotencyKeyEntity completed      = pending.toBuilder()
                .status(PaymentStatus.COMPLETED)
                .paymentReference("YNO-SINGLE")
                .completedAt(Instant.now())
                .build();
        MockPaymentResult    mockResult     = MockPaymentResult.builder()
                .success(true).status("COMPLETED").paymentReference("YNO-SINGLE").build();

        // First call: cache miss, DB new claim
        when(redisCache.get(MERCHANT_ID, KEY)).thenReturn(Optional.empty());
        when(persistenceService.claimKey(KEY, MERCHANT_ID, request))
                .thenReturn(IdempotencyClaimResult.newClaim(pending))            // 1st call
                .thenReturn(IdempotencyClaimResult.existingClaim(completed))    // 2nd-5th calls
                .thenReturn(IdempotencyClaimResult.existingClaim(completed))
                .thenReturn(IdempotencyClaimResult.existingClaim(completed))
                .thenReturn(IdempotencyClaimResult.existingClaim(completed));
        when(mockProcessor.process(request)).thenReturn(mockResult);
        when(persistenceService.markCompleted(any(), any(), any())).thenReturn(completed);

        int newCharges = 0;
        int duplicates = 0;
        for (int i = 0; i < 5; i++) {
            PaymentResponse r = service.processPayment(KEY, MERCHANT_ID, request);
            if (r.isDuplicate()) duplicates++;
            else                 newCharges++;
        }

        assertThat(newCharges).isEqualTo(1);
        assertThat(duplicates).isEqualTo(4);
        verify(mockProcessor, times(1)).process(request); // processor called ONCE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test: declined payment is stored and returned for duplicates
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Declined payment result is stored and returned for duplicate calls")
    void declinedPayment_resultStoredAndReturnedForDuplicates() {
        PaymentRequest       request = buildRequest("450.00", "EGP");
        IdempotencyKeyEntity pending = buildEntity(PaymentStatus.PENDING);
        IdempotencyKeyEntity failed  = pending.toBuilder()
                .status(PaymentStatus.FAILED)
                .failureReason("INSUFFICIENT_FUNDS")
                .completedAt(Instant.now())
                .build();
        MockPaymentResult mockResult = MockPaymentResult.builder()
                .success(false).status("DECLINED").failureReason("INSUFFICIENT_FUNDS").build();

        when(redisCache.get(any(), any())).thenReturn(Optional.empty());
        when(persistenceService.claimKey(KEY, MERCHANT_ID, request))
                .thenReturn(IdempotencyClaimResult.newClaim(pending));
        when(mockProcessor.process(request)).thenReturn(mockResult);
        when(persistenceService.markCompleted(any(), any(), any())).thenReturn(failed);

        PaymentResponse response = service.processPayment(KEY, MERCHANT_ID, request);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.isDuplicate()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test: getPaymentStatus – key not found → exception
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPaymentStatus – unknown key → IdempotencyKeyNotFoundException")
    void getPaymentStatus_unknownKey_throwsNotFoundException() {
        when(redisCache.get(any(), any())).thenReturn(Optional.empty());
        when(persistenceService.findByKeyAndMerchant(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPaymentStatus("unknown-key", MERCHANT_ID))
                .isInstanceOf(com.crescent.pharmacy.idempotency.exception.IdempotencyKeyNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PaymentRequest buildRequest(String amount, String currency) {
        return PaymentRequest.builder()
                .customerId("CUST_001")
                .amount(new BigDecimal(amount))
                .currency(currency)
                .paymentMethod(PaymentMethodDetails.builder()
                        .type("CARD").cardLastFour("4242").cardBrand("VISA").build())
                .build();
    }

    private IdempotencyKeyEntity buildEntity(PaymentStatus status) {
        return IdempotencyKeyEntity.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(KEY)
                .merchantId(MERCHANT_ID)
                .customerId("CUST_001")
                .amount(new BigDecimal("50.00"))
                .currency("EGP")
                .paymentMethod("CARD")
                .status(status)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
    }
}
