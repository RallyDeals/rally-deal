package com.groupdeal.dealservice.web.dto;

import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.domain.DealStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Response shape for POST /deals, GET /deals, GET /deals/{id} (design doc §5.1 / §5.2).
 * timeRemainingSeconds is computed, not stored — null while the deal hasn't started.
 */
public record DealResponse(
        Long id,
        Long productId,
        Long sellerId,
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
        OffsetDateTime createdAt
) {
    public static DealResponse from(Deal deal) {
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
                deal.getCreatedAt()
        );
    }
}
