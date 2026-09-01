package com.groupdeal.dealservice.web.dto;

import com.groupdeal.dealservice.domain.DealStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Enriched, paginated deal response used by GET /deals.
 *
 * Contains all deal table fields plus three computed values and
 * five fields enriched from Catalog Service's bulk-summary endpoint.
 */
public record DealOverview(
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

        // ── computed ────────────────────────────────────────────────────────
        /** max(0, minParticipants - currentParticipants) */
        int neededCount,
        /** (currentParticipants * 100) / dealStock, capped at 100 */
        int progressPercent,
        /** max(0, seconds between now and endTime); 0 if endTime null or past */
        long timeRemainingInSeconds,

        // ── enriched from catalog ───────────────────────────────────────────
        String productName,
        String productImageUrl,
        String category,
        String sku,
        String sellerName
) { }
