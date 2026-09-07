package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.client.dto.ProductSummaryDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "groupdeal.clients.catalog.stub", havingValue = "true", matchIfMissing = true)
public class CatalogClientStub implements CatalogClient {

    public static final UUID STUB_SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public Optional<ProductDto> getProduct(UUID productId) {
        UUID categoryId = UUID.nameUUIDFromBytes(("category-" + productId).getBytes());
        return Optional.of(new ProductDto(productId, STUB_SELLER_ID, new BigDecimal("199.99"), categoryId));
    }

    @Override
    public Map<UUID, ProductSummaryDto> getProductSummaries(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> new ProductSummaryDto(
                        id,
                        "Stub Product " + id.toString().substring(0, 8),
                        null,
                        "Stub Category",
                        "SKU-" + id.toString().substring(0, 8).toUpperCase(),
                        "Stub Seller",
                        null,
                        null
                )
        ));
    }
}
