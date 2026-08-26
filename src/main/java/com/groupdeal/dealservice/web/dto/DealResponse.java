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
        OffsetDateTime createdAt
) { }
