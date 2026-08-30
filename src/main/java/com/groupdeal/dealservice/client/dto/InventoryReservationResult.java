package com.groupdeal.dealservice.client.dto;

/**
 * Shape Deal Service expects back from Inventory Service's
 * POST /inventory/{productId}/reserve (design doc §6.2).
 */
public record InventoryReservationResult(
        boolean success,
        String reason, // populated only when success = false, e.g. "INSUFFICIENT_STOCK"
        Integer availableStock
) {
    public InventoryReservationResult(boolean success, String reason) {
        this(success, reason, null);
    }
}
