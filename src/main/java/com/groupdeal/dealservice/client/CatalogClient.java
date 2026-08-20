package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.ProductDto;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sync dependency on Catalog Service (design doc §6.2).
 * Two implementations wired via `groupdeal.clients.catalog.stub` (application.yml):
 *   - CatalogClientStub    (default — Catalog Service doesn't exist yet)
 *   - CatalogClientHttp    (real WebClient call, ready for when it does)
 */
public interface CatalogClient {

    /**
     * @return empty if the product doesn't exist (maps to 404 PRODUCT_NOT_FOUND).
     */
    Optional<ProductDto> getProduct(UUID productId);

    /**
     * Batch fetch — returns a map keyed by productId. Missing products are omitted.
     */
    Map<UUID, ProductDto> getProducts(Collection<UUID> productIds);
}
