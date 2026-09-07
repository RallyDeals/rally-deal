package com.groupdeal.dealservice.web.dto;

import com.groupdeal.dealservice.domain.DealStatus;

import java.util.List;
import java.util.UUID;

public record BulkDealRequest(List<UUID> ids, List<DealStatus> statuses) {
}
