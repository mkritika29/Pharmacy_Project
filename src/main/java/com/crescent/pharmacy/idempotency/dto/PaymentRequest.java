package com.crescent.pharmacy.idempotency.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Inbound payment request from a Crescent Pharmacy POS system.
 * The idempotency key is supplied via the X-Idempotency-Key HTTP header (not in this body).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    @NotBlank(message = "customerId is required")
    @Size(max = 255)
    private String customerId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code (e.g. EGP, MAD, TND)")
    private String currency;

    @NotNull(message = "paymentMethod is required")
    @Valid
    private PaymentMethodDetails paymentMethod;

    @Size(max = 500)
    private String description;

    /** Optional key-value metadata (e.g. prescription ID, pharmacy branch). */
    private Map<String, String> metadata;
}
