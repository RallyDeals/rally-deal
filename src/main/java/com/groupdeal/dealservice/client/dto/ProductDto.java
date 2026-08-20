package com.groupdeal.dealservice.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shape Deal Service expects back from Catalog Service's GET /products/{productId}.
 * Includes display fields needed for DealResponse enrichment.
 */
public record ProductDto(
        UUID productId,
        UUID sellerId,
        BigDecimal basePrice,
        String name,
        String imageUrl,
        String category,
        String sku,
        String sellerName
) {
    public ProductDto(UUID productId, UUID sellerId, BigDecimal basePrice) {
        this(productId, sellerId, basePrice, null, null, null, null, null);
    }
}
