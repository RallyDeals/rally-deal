package com.groupdeal.dealservice.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for reserve-slot / release-slot / authorize-slot / release-authorized-slot
 * (design doc §5.4–§5.7). Contains only the caller-generated idempotency key.
 */
public record SlotRequest(
        @NotNull UUID requestId
) {
}
