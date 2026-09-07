package com.groupdeal.dealservice.web.dto;

/**
 * Analytics summary for GET /deals/analytics.
 *
 * activeDeals:   status IN (ACTIVE, PENDING)
 * completedDeals: status IN (SUCCEEDED, FAILED)
 * successRate:   completedDeals > 0 ? (succeededDeals / completedDeals * 100) : 0.0
 */
public record DealAnalyticsResponse(
        long totalDeals,
        long dealsThisMonth,
        long activeDeals,
        long dealsToday,
        long completedDeals,
        double successRate
) {}
