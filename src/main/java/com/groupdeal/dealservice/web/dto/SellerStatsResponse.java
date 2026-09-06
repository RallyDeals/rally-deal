package com.groupdeal.dealservice.web.dto;

import java.math.BigDecimal;

/**
 * Seller statistics response for GET /deals/seller-stats.
 *
 * activeDealCnt: count of deals with status ACTIVE or PENDING
 * totalRevenue: sum of (dealPrice * authorizedCount) for all SUCCEEDED deals
 * participantsJoined: sum of authorizedCount for all SUCCEEDED deals
 * avgCompletionRate: succeededCount / (succeededCount + failedCount)
 */
public record SellerStatsResponse(
        long activeDealCnt,
        BigDecimal totalRevenue,
        long participantsJoined,
        double avgCompletionRate
) {}
