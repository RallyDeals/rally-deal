package com.groupdeal.dealservice.web.dto;

import java.util.UUID;

public record HasActiveDealsResponse(
        UUID productId,
        boolean hasActiveDeals
) {
}
