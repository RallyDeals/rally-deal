package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.ProductDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "groupdeal.clients.catalog.stub", havingValue = "false")
public class CatalogClientHttp implements CatalogClient {

    private final RestClient restClient;

    public CatalogClientHttp(
            RestClient.Builder restClientBuilder,
            @Value("${groupdeal.clients.catalog.base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // TimeLimiter doesn't apply to blocking RestClient calls, so the timeout
        // has to live on the HTTP client itself (matches timelimiter.catalogService=3s).
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "catalogService", fallbackMethod = "getProductFallback")
    public Optional<ProductDto> getProduct(UUID productId) {
        try {
            ProductDto productDto = restClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(ProductDto.class);

            // A 200 with an empty/null body is a bug on Catalog's side, not a 404 —
            // do not silently map it to "product not found".
            if (productDto == null) {
                throw new IllegalStateException("Catalog Service returned an empty body for product " + productId);
            }
            return Optional.of(productDto);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Catalog Service returned 404 for product {}", productId);
            return Optional.empty();
        }
    }

    /**
     * Invoked by the circuit breaker on any failing catalog call (timeout, 5xx,
     * connect failure, or open circuit). Returning empty would be wrong — that maps
     * to 404 PRODUCT_NOT_FOUND downstream, not "catalog is down" — so rethrow.
     */
    @SuppressWarnings("unused")
    private Optional<ProductDto> getProductFallback(UUID productId, Throwable t) {
        log.error("Catalog Service unavailable while fetching product {} (circuit open or request failed)", productId, t);
        throw new IllegalStateException("Catalog Service unavailable while fetching product " + productId, t);
    }
}