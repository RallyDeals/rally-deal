package com.groupdeal.dealservice.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * Compact product shape returned by Catalog Service's bulk
 * GET /products/summary?ids=... endpoint.
 *
 * All fields except productId are nullable — if catalog is unavailable
 * the service degrades gracefully and leaves them null.
 */
public record ProductSummaryDto(
        UUID productId,
        String name,
        String imageUrl,
        String category,
        String sku,
        String sellerName,
        String description,
        List<String> images
) { }
