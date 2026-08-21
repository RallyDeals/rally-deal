package com.groupdeal.dealservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupdeal.dealservice.client.dto.ProductDto;
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

@Slf4j
@Component
@ConditionalOnProperty(name = "groupdeal.clients.catalog.stub", havingValue = "false")
public class CatalogClientHttp implements CatalogClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CatalogClientHttp(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${groupdeal.clients.catalog.base-url}") String baseUrl) {
        this.objectMapper = objectMapper;
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


    private ProductDto mapToProductDto(UUID productId, JsonNode json) {
        String name = json.has("name") ? json.get("name").asText(null) : null;
        String imageUrl = json.has("imageUrl") ? json.get("imageUrl").asText(null) : null;
        String category = null;
        if (json.has("category") && json.get("category") != null && !json.get("category").isNull()) {
            JsonNode cat = json.get("category");
            category = cat.has("name") ? cat.get("name").asText(null) : null;
        }
        String sku = json.has("sku") ? json.get("sku").asText(null) : null;
        String sellerIdStr = json.has("sellerId") ? json.get("sellerId").asText(null) : null;
        UUID sellerId = sellerIdStr != null ? UUID.fromString(sellerIdStr) : null;
        String sellerName = json.has("sellerName") ? json.get("sellerName").asText(null) : null;
        BigDecimal basePrice = json.has("basePrice") ? json.get("basePrice").decimalValue() : null;

        return new ProductDto(productId, sellerId, basePrice, name, imageUrl, category, sku, sellerName);
    }

    @SuppressWarnings("unused")
    private Optional<ProductDto> getProductFallback(UUID productId, Throwable t) {
        log.error("Catalog Service unavailable while fetching product {} (circuit open or request failed)", productId, t);
        throw new IllegalStateException("Catalog Service unavailable while fetching product " + productId, t);
    }
}