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
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP implementation of InventoryClient.
 *
 * The inventory service now returns a structured JSON body
 * {@code { "success": bool, "reason": str|null, "availableStock": int|null }}
 * for both reserve-deal and reserve-order endpoints — always HTTP 200.
 * No exception-catching needed; we just deserialize and map.
 */
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
        JsonNode json = restClient.post()
                .uri("/inventory/{productId}/reserve-deal", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("quantity", quantity))
                .retrieve()
                .body(JsonNode.class);

        if (json == null || !json.has("success")) {
            // Unexpected empty body — treat as success (backward-compat with old inventory deployments)
            log.warn("Inventory Service returned unexpected body for product {} reservation; assuming success", productId);
            return new InventoryReservationResult(true, null, null);
        }

        boolean success = json.get("success").asBoolean(false);
        String reason = json.has("reason") && !json.get("reason").isNull()
                ? json.get("reason").asText()
                : null;
        Integer availableStock = json.has("availableStock") && !json.get("availableStock").isNull()
                ? json.get("availableStock").asInt()
                : null;

        return new InventoryReservationResult(success, reason, availableStock);
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "releaseFallback")
    public void release(UUID productId, Integer quantity) {
        try {
            restClient.post()
                    .uri("/inventory/{productId}/release-deal", productId)
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
        log.error("Inventory service unavailable while reserving stock for product {} (quantity={}): {}", productId, quantity, t.getMessage());
        return new InventoryReservationResult(false, "INVENTORY_SERVICE_UNAVAILABLE", null);
    }

    @SuppressWarnings("unused")
    private void releaseFallback(UUID productId, Integer quantity, Throwable t) {
        log.error("Inventory service unavailable while releasing stock for product {} (quantity={}): {}", productId, quantity, t.getMessage());
    }
}
