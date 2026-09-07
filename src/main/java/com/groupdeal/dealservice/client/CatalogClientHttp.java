package com.groupdeal.dealservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.client.dto.ProductSummaryDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Component
@ConditionalOnProperty(name = "groupdeal.clients.catalog.stub", havingValue = "false")
public class CatalogClientHttp implements CatalogClient {

    private final RestClient restClient;

    public CatalogClientHttp(
            RestClient.Builder restClientBuilder,
            @Value("${groupdeal.clients.catalog.base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "catalogService", fallbackMethod = "getProductFallback")
    public Optional<ProductDto> getProduct(UUID productId) {
        try {
            JsonNode json = restClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(JsonNode.class);

            if (json == null || json.isNull()) {
                throw new IllegalStateException("Catalog Service returned an empty body for product " + productId);
            }

            return Optional.of(mapToProductDto(productId, json));
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Catalog Service returned 404 for product {}", productId);
            return Optional.empty();
        }
    }

    /**
     * Bulk summary fetch — single HTTP call for all productIds on a page.
     * Falls back to an empty map when the catalog circuit is open or the request fails,
     * so the listing endpoint degrades gracefully (deals are returned with null product fields).
     */
    @Override
    @CircuitBreaker(name = "catalogService", fallbackMethod = "getProductSummariesFallback")
    public Map<UUID, ProductSummaryDto> getProductSummaries(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String ids = productIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));

        JsonNode json = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/products/summary")
                        .queryParam("ids", ids)
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (json == null || json.isNull() || !json.isArray()) {
            log.warn("Catalog Service returned unexpected body for bulk summary: {}", json);
            return Collections.emptyMap();
        }

        Map<UUID, ProductSummaryDto> result = new HashMap<>();
        for (JsonNode node : json) {
            ProductSummaryDto summary = mapToProductSummaryDto(node);
            if (summary != null) {
                result.put(summary.productId(), summary);
            }
        }
        return result;
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────────

    private ProductDto mapToProductDto(UUID productId, JsonNode json) {
        String name = json.has("name") ? json.get("name").asText(null) : null;
        String imageUrl = json.has("imageUrl") ? json.get("imageUrl").asText(null) : null;
        String category = null;
        UUID categoryId = null;

        if (json.has("categoryId") && json.get("categoryId") != null && !json.get("categoryId").isNull()) {
            try {
                categoryId = UUID.fromString(json.get("categoryId").asText());
            } catch (IllegalArgumentException ignored) {}
        }

        if (json.has("category") && json.get("category") != null && !json.get("category").isNull()) {
            JsonNode cat = json.get("category");
            if (cat.isObject()) {
                category = cat.has("name") ? cat.get("name").asText(null) : null;
                if (categoryId == null && cat.has("id") && !cat.get("id").isNull()) {
                    try {
                        categoryId = UUID.fromString(cat.get("id").asText());
                    } catch (IllegalArgumentException ignored) {}
                }
            } else if (cat.isTextual()) {
                category = cat.asText();
            }
        }
        String sku = json.has("sku") ? json.get("sku").asText(null) : null;
        String sellerIdStr = json.has("sellerId") ? json.get("sellerId").asText(null) : null;
        UUID sellerId = sellerIdStr != null ? UUID.fromString(sellerIdStr) : null;
        String sellerName = json.has("sellerName") ? json.get("sellerName").asText(null) : null;
        BigDecimal basePrice = json.has("basePrice") ? json.get("basePrice").decimalValue() : null;

        String description = json.has("description") ? json.get("description").asText(null) : null;
        List<String> images = null;
        if (json.has("images") && json.get("images").isArray()) {
            images = StreamSupport.stream(json.get("images").spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());
        }

        return new ProductDto(productId, sellerId, basePrice, name, imageUrl, category, categoryId, sku, sellerName, description, images);
    }

    private ProductSummaryDto mapToProductSummaryDto(JsonNode node) {
        if (!node.has("id") && !node.has("productId")) {
            log.warn("Catalog summary item missing id field: {}", node);
            return null;
        }

        UUID productId;
        try {
            String rawId = node.has("id") ? node.get("id").asText() : node.get("productId").asText();
            productId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            log.warn("Catalog summary item has unparseable id: {}", node);
            return null;
        }

        String name = node.has("name") ? node.get("name").asText(null) : null;
        String imageUrl = node.has("imageUrl") ? node.get("imageUrl").asText(null) : null;
        String sku = node.has("sku") ? node.get("sku").asText(null) : null;
        String sellerName = node.has("sellerName") ? node.get("sellerName").asText(null) : null;
        String description = node.has("description") ? node.get("description").asText(null) : null;

        // category can be a string or an object with a "name" field
        String category = null;
        if (node.has("category") && !node.get("category").isNull()) {
            JsonNode cat = node.get("category");
            if (cat.isObject()) {
                category = cat.has("name") ? cat.get("name").asText(null) : null;
            } else if (cat.isTextual()) {
                category = cat.asText();
            }
        }

        // images as a list of strings
        List<String> images = null;
        if (node.has("images") && node.get("images").isArray()) {
            images = StreamSupport.stream(node.get("images").spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());
        }

        return new ProductSummaryDto(productId, name, imageUrl, category, sku, sellerName, description, images);
    }

    // ── Fallbacks ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private Optional<ProductDto> getProductFallback(UUID productId, Throwable t) {
        log.error("Catalog Service unavailable while fetching product {} (circuit open or request failed)", productId, t);
        throw new IllegalStateException("Catalog Service unavailable while fetching product " + productId, t);
    }

    @SuppressWarnings("unused")
    private Map<UUID, ProductSummaryDto> getProductSummariesFallback(List<UUID> productIds, Throwable t) {
        log.warn("Catalog Service unavailable for bulk product summary (circuit open or request failed) — degrading gracefully", t);
        return Collections.emptyMap();
    }
}