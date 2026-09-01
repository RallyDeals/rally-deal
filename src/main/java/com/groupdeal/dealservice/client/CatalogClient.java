package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.client.dto.ProductSummaryDto;

import java.util.List;
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
     * Bulk fetch of compact product summaries.
     * Used by the deal-list enrichment path — single call for all productIds on a page.
     *
     * @return map from productId to summary; missing IDs are simply absent from the map.
     *         Returns an empty map when the catalog is unavailable (graceful degradation).
     */
    Map<UUID, ProductSummaryDto> getProductSummaries(List<UUID> productIds);
}
