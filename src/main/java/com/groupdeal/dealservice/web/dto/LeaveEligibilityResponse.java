package com.groupdeal.dealservice.web.dto;

import java.util.UUID;

/**
 * Response shape for GET /internal/deals/{id}/check-leave-eligible (design doc §5.8).
 */
public record LeaveEligibilityResponse(
        boolean eligible,
        UUID dealId,
        String reason  // null when eligible; "TOO_CLOSE_TO_END_TIME" | "DEAL_NOT_ACTIVE" when not
) {
    public static LeaveEligibilityResponse eligible(UUID dealId) {
        return new LeaveEligibilityResponse(true, dealId, null);
    }

    public static LeaveEligibilityResponse notEligible(UUID dealId, String reason) {
        return new LeaveEligibilityResponse(false, dealId, reason);
    }
}
