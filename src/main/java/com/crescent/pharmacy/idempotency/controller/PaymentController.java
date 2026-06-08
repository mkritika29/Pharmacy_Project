package com.crescent.pharmacy.idempotency.controller;

import com.crescent.pharmacy.idempotency.dto.PaymentRequest;
import com.crescent.pharmacy.idempotency.dto.PaymentResponse;
import com.crescent.pharmacy.idempotency.entity.PaymentAuditEntity;
import com.crescent.pharmacy.idempotency.repository.PaymentAuditRepository;
import com.crescent.pharmacy.idempotency.service.PaymentIdempotencyService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for idempotent payment processing.
 *
 * All endpoints require the {@code X-Idempotency-Key} header.
 * Multi-tenancy is supported via the {@code X-Merchant-Id} header
 * (defaults to "default" when omitted).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    static final String MERCHANT_ID_HEADER     = "X-Merchant-Id";
    static final String DEFAULT_MERCHANT       = "default";

    private final PaymentIdempotencyService  idempotencyService;
    private final PaymentAuditRepository     auditRepository;

    // ── POST /api/v1/payments ─────────────────────────────────────────────────

    /**
     * Submit a payment for processing.
     *
     * <ul>
     *   <li>First call with a given key → processes the payment, returns {@code 201 Created}</li>
     *   <li>Subsequent calls with the same key → returns the original result, {@code 200 OK},
     *       with {@code "duplicate": true} in the body</li>
     * </ul>
     */
    @PostMapping
    @Timed(value = "payment.api.process", description = "End-to-end payment processing time")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER)
            @NotBlank(message = "X-Idempotency-Key header must not be blank")
            String idempotencyKey,

            @RequestHeader(value = MERCHANT_ID_HEADER, defaultValue = DEFAULT_MERCHANT)
            String merchantId,

            @RequestBody @Valid PaymentRequest request
    ) {
        PaymentResponse response = idempotencyService.processPayment(idempotencyKey, merchantId, request);

        // 201 Created for brand-new charges; 200 OK for idempotent replays
        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    // ── GET /api/v1/payments/{key} ────────────────────────────────────────────

    /**
     * Retrieve the current status and result of a payment by its idempotency key.
     */
    @GetMapping("/{idempotencyKey}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(
            @PathVariable String idempotencyKey,
            @RequestHeader(value = MERCHANT_ID_HEADER, defaultValue = DEFAULT_MERCHANT)
            String merchantId
    ) {
        PaymentResponse response = idempotencyService.getPaymentStatus(idempotencyKey, merchantId);
        return ResponseEntity.ok(response);
    }

    // ── GET /api/v1/payments/{key}/audit ─────────────────────────────────────

    /**
     * Retrieve the full audit trail for a specific idempotency key.
     * Shows every event: new payment, duplicates blocked, completion.
     * Useful for verifying idempotency behaviour during testing.
     */
    @GetMapping("/{idempotencyKey}/audit")
    public ResponseEntity<List<PaymentAuditEntity>> getAuditTrail(
            @PathVariable String idempotencyKey,
            @RequestHeader(value = MERCHANT_ID_HEADER, defaultValue = DEFAULT_MERCHANT)
            String merchantId
    ) {
        List<PaymentAuditEntity> trail =
                auditRepository.findByIdempotencyKeyAndMerchantIdOrderByCreatedAtAsc(
                        idempotencyKey, merchantId);
        return ResponseEntity.ok(trail);
    }
}
