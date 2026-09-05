package com.groupdeal.dealservice.web.dto;

import com.groupdeal.dealservice.domain.DealStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full deal detail response used by GET /deals/{id}.
 *
 * Everything in {@link DealOverview} plus richer product data
 * (description and full image list) fetched from Catalog Service's
 * single-product endpoint.
 */
public record DealDetails(
        // ── deal table fields ───────────────────────────────────────────────
        UUID id,
        UUID productId,
        UUID sellerId,
        UUID categoryId,
        BigDecimal originalPrice,
        BigDecimal dealPrice,
        int dealStock,
        int currentParticipants,
        int minParticipants,
        int authorizedCount,
        DealStatus status,
        int durationMinutes,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        OffsetDateTime createdAt,

        // ── enriched from catalog (single-product endpoint) ─────────────────
        String productDescription,
        List<String> productImages
) { }
