package com.groupdeal.dealservice.web.dto;

import java.math.BigDecimal;

public record DealAnalyticsResponse(
        long totalDeals,
        long dealsCreatedThisMonth,
        long activeDeals,
        long dealsCreatedToday,
        long completedDeals,
        BigDecimal successRate
) {}
