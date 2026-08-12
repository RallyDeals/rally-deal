package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.ProductDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Stand-in for Catalog Service. Returns a plausible product for ANY productId so the
 * create-deal flow is fully testable end to end before Catalog Service is built.
 *
 * Active by default (groupdeal.clients.catalog.stub=true in application.yml).
 * Swap to CatalogClientHttp by setting that property to false once the real service exists —
 * no controller/service code needs to change, since both implement CatalogClient.
 */
@Component
@ConditionalOnProperty(name = "groupdeal.clients.catalog.stub", havingValue = "true", matchIfMissing = true)
public class CatalogClientStub implements CatalogClient {

    /** Stable stub seller ID — used in tests to match the X-User-Id header. */
    public static final UUID STUB_SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public Optional<ProductDto> getProduct(UUID productId) {
        // TODO: replace with real HTTP call once Catalog Service exists.
        // For now: every product "exists", is owned by STUB_SELLER_ID, and costs 199.99.
        return Optional.of(new ProductDto(productId, STUB_SELLER_ID, new BigDecimal("199.99")));
    }
}
