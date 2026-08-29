package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.ProductDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
@ConditionalOnProperty(name = "groupdeal.clients.catalog.stub", havingValue = "true", matchIfMissing = true)
public class CatalogClientStub implements CatalogClient {

    public static final UUID STUB_SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public Optional<ProductDto> getProduct(UUID productId) {
        UUID categoryId = UUID.nameUUIDFromBytes(("category-" + productId).getBytes());
        return Optional.of(new ProductDto(productId, STUB_SELLER_ID, new BigDecimal("199.99"), categoryId));
    }
}
