package com.groupdeal.dealservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.groupdeal.dealservice.client.dto.InventoryReservationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "groupdeal.clients.inventory.stub", havingValue = "false")
public class InventoryClientHttp implements InventoryClient {

    private final RestClient restClient;

    public InventoryClientHttp(
            RestClient.Builder restClientBuilder,
            @Value("${groupdeal.clients.inventory.base-url}") String baseUrl) {
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
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "reserveFallback")
    public InventoryReservationResult reserve(UUID productId, Integer quantity) {
        try {
            JsonNode json = restClient.post()
                    .uri("/inventory/{productId}/reserve", productId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("quantity", quantity))
                    .retrieve()
                    .body(JsonNode.class);

            if (json != null && json.has("success")) {
                boolean success = json.get("success").asBoolean(false);
                String reason = json.has("reason") && !json.get("reason").isNull()
                        ? json.get("reason").asText()
                        : null;
                return new InventoryReservationResult(success, reason);
            }

            return new InventoryReservationResult(true, null);
        } catch (HttpClientErrorException.BadRequest e) {
            log.info("Inventory Service returned 400 Bad Request reserving stock for product {}", productId);
            return new InventoryReservationResult(false, "INSUFFICIENT_STOCK");
        }
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "releaseFallback")
    public void release(UUID productId, Integer quantity) {
        try {
            restClient.post()
                    .uri("/inventory/{productId}/release", productId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("quantity", quantity))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to release inventory for product {} (quantity={}): {}", productId, quantity, e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private InventoryReservationResult reserveFallback(UUID productId, Integer quantity, Throwable t) {
        log.error("Inventory Service unavailable while reserving stock for product {} (circuit open or request failed)", productId, t);
        throw new IllegalStateException("Inventory Service unavailable while reserving stock for product " + productId, t);
    }

    @SuppressWarnings("unused")
    private void releaseFallback(UUID productId, Integer quantity, Throwable t) {
        log.error("Inventory Service unavailable while releasing stock for product {} (circuit open or request failed)", productId, t);
        throw new IllegalStateException("Inventory Service unavailable while releasing stock for product " + productId, t);
    }
}
