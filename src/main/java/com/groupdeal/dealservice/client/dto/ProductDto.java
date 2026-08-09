package com.groupdeal.dealservice.client.dto;

import java.math.BigDecimal;

/**
 * Shape Deal Service expects back from Catalog Service's GET /products/{productId}.
 * Trimmed to only what Deal Service actually needs (ownership + base price) —
 * NOT a full mirror of Catalog Service's product model.
 */
public record ProductDto(
        Long productId,
        Long sellerId,
        BigDecimal basePrice
) {
}
