package com.groupdeal.dealservice.web.dto;

import com.groupdeal.dealservice.domain.DealStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape for reserve-slot (§5.4), release-slot (§5.5), authorize-slot (§5.6),
 * release-authorized-slot (§5.7). Covers both success and rejection cases.
 *
 * When success=true, all fields are populated.
 * When success=false, only dealId + reason are populated (reason explains rejection).
 */
public record SlotResponse(
        boolean success,
        UUID dealId,
        String reason,            // null on success; "DEAL_FULL" | "DEAL_NOT_JOINABLE" on rejection
        BigDecimal dealPrice,     // returned by reserve-slot so Participation Service can forward it
        Integer currentParticipants,
        Integer authorizedCount,
        Integer dealStock,
        DealStatus status,
        OffsetDateTime startTime,
        OffsetDateTime endTime
) {
    /** Convenience factory for a successful slot response. */
    public static SlotResponse success(UUID dealId, BigDecimal dealPrice,
                                       Integer currentParticipants, Integer authorizedCount,
                                       Integer dealStock, DealStatus status,
                                       OffsetDateTime startTime, OffsetDateTime endTime) {
        return new SlotResponse(true, dealId, null, dealPrice,
                currentParticipants, authorizedCount, dealStock, status, startTime, endTime);
    }

    /** Convenience factory for a rejected slot response. */
    public static SlotResponse rejected(UUID dealId, String reason) {
        return new SlotResponse(false, dealId, reason, null, null, null, null, null, null, null);
    }
}
