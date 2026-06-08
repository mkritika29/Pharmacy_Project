package com.crescent.pharmacy.idempotency.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Describes the payment instrument the customer is using.
 * Type must be one of: CARD, MOBILE_WALLET, BANK_TRANSFER
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodDetails {

    @NotBlank(message = "Payment method type is required (CARD, MOBILE_WALLET, BANK_TRANSFER)")
    private String type;

    // ── CARD fields ───────────────────────────────────────────────────────────
    private String cardLastFour;
    private String cardBrand;          // VISA, MASTERCARD, AMEX

    // ── MOBILE_WALLET fields ──────────────────────────────────────────────────
    private String walletType;         // VODAFONE_CASH, FAWRY, ORANGE_MONEY, INSTAPAY
    private String walletPhoneNumber;
}
