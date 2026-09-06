package com.groupdeal.dealservice.repository;

import java.math.BigDecimal;

public interface SellerStatsProjection {
    long getActiveDealCnt();
    BigDecimal getTotalRevenue();
    long getParticipantsJoined();
    long getSucceededCnt();
    long getFailedCnt();
}