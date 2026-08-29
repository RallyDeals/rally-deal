package com.groupdeal.dealservice.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for POST /deals (design doc §5.1).
 * sellerId is deliberately NOT a field — it comes from the JWT (X-User-Id header),
 * never trusted from the request body.
 */
public record CreateDealRequest(
        @NotNull UUID productId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal dealPrice,
        @NotNull @Min(1) Integer dealStock,
        @NotNull @Min(1) Integer minParticipants,
        @NotNull @Min(1) Integer durationMinutes
) {
}
