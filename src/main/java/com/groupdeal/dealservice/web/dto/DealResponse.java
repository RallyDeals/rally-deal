package com.groupdeal.dealservice.web.dto;

import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.domain.DealStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response shape for POST /deals, GET /deals, GET /deals/{id} (design doc §5.1 / §5.2).
 * timeRemainingSeconds is computed, not stored — null while the deal hasn't started.
 * Product display fields are enriched from Catalog Service at response time.
 */
public record DealResponse(
        UUID id,
        UUID productId,
        UUID sellerId,
        BigDecimal originalPrice,
        BigDecimal dealPrice,
        Integer dealStock,
        Integer currentParticipants,
        Integer authorizedCount,
        Integer minParticipants,
        DealStatus status,
        OffsetDateTime startTime,
        Integer durationMinutes,
        OffsetDateTime endTime,
        Long timeRemainingSeconds,
        OffsetDateTime createdAt,
        String productName,
        String productImageUrl,
        String category,
        String sku,
        String sellerName
) {
    public static DealResponse from(Deal deal) {
        Long remaining = null;
        if (deal.getEndTime() != null) {
            long seconds = Duration.between(OffsetDateTime.now(), deal.getEndTime()).getSeconds();
            remaining = Math.max(seconds, 0);
        }
        return new DealResponse(
                deal.getId(), deal.getProductId(), deal.getSellerId(),
                deal.getOriginalPrice(), deal.getDealPrice(), deal.getDealStock(),
                deal.getCurrentParticipants(), deal.getAuthorizedCount(), deal.getMinParticipants(),
                deal.getStatus(), deal.getStartTime(), deal.getDurationMinutes(),
                deal.getEndTime(), remaining, deal.getCreatedAt(),
                null, null, null, null, null);
    }

    public static DealResponse from(Deal deal, ProductDto product) {
        Long remaining = null;
        if (deal.getEndTime() != null) {
            long seconds = Duration.between(OffsetDateTime.now(), deal.getEndTime()).getSeconds();
            remaining = Math.max(seconds, 0);
        }
        return new DealResponse(
                deal.getId(),
                deal.getProductId(),
                deal.getSellerId(),
                deal.getOriginalPrice(),
                deal.getDealPrice(),
                deal.getDealStock(),
                deal.getCurrentParticipants(),
                deal.getAuthorizedCount(),
                deal.getMinParticipants(),
                deal.getStatus(),
                deal.getStartTime(),
                deal.getDurationMinutes(),
                deal.getEndTime(),
                remaining,
                deal.getCreatedAt(),
                product != null ? product.name() : null,
                product != null ? product.imageUrl() : null,
                product != null ? product.category() : null,
                product != null ? product.sku() : null,
                product != null ? product.sellerName() : null
        );
    }

    public static DealResponse from(Deal deal, Map<UUID, ProductDto> productMap) {
        ProductDto product = productMap != null ? productMap.get(deal.getProductId()) : null;
        return from(deal, product);
    }
}
