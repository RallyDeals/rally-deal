package com.groupdeal.dealservice.web;

import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.HasActiveDealsResponse;
import com.groupdeal.dealservice.web.dto.LeaveEligibilityResponse;
import com.groupdeal.dealservice.web.dto.SlotRequest;
import com.groupdeal.dealservice.web.dto.SlotResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal, service-to-service sync endpoints (design doc §4.2).
 * NOT routed through the API Gateway — called directly by Participation Service
 * and Order Service within the cluster.
 */
@RestController
@RequestMapping("/internal/deals")
@RequiredArgsConstructor
public class InternalDealController {

    private final DealService dealService;

    /**
     * DS-06: Reserve a slot on join (§5.4).
     * Called by Participation Service. Increments current_participants;
     * first successful reservation flips pending→active and sets start_time/end_time.
     */
    @PostMapping("/{id}/reserve-slot")
    public SlotResponse reserveSlot(@PathVariable UUID id,
                                    @Valid @RequestBody SlotRequest request) {
        return dealService.reserveSlot(id, request.requestId());
    }

    /**
     * DS-07: Release a slot whose payment was declined before authorization (§5.5).
     * Called by Order Service. Decrements current_participants only.
     */
    @PostMapping("/{id}/release-slot")
    public SlotResponse releaseSlot(@PathVariable UUID id,
                                    @Valid @RequestBody SlotRequest request) {
        return dealService.releaseSlot(id, request.requestId());
    }

    /**
     * DS-11: Mark a reserved slot as payment-authorized (§5.6).
     * Called by Order Service on payment.authorized. Increments authorized_count;
     * may flip active→succeeded if this fills deal_stock.
     */
    @PostMapping("/{id}/authorize-slot")
    public SlotResponse authorizeSlot(@PathVariable UUID id,
                                      @Valid @RequestBody SlotRequest request) {
        return dealService.authorizeSlot(id, request.requestId());
    }

    /**
     * DS-12: Release an already-authorized slot (§5.7).
     * Called by Order Service when participant left after payment was authorized.
     * Decrements both current_participants and authorized_count.
     */
    @PostMapping("/{id}/release-authorized-slot")
    public SlotResponse releaseAuthorizedSlot(@PathVariable UUID id,
                                              @Valid @RequestBody SlotRequest request) {
        return dealService.releaseAuthorizedSlot(id, request.requestId());
    }

    /**
     * DS-13: Check whether a buyer is allowed to leave a deal (§5.8).
     * Called by Participation Service before committing to a leave.
     * Read-only: deal must be active and >10 minutes before end_time.
     */
    @GetMapping("/{id}/check-leave-eligible")
    public LeaveEligibilityResponse checkLeaveEligible(@PathVariable UUID id) {
        return dealService.checkLeaveEligible(id);
    }

    /**
     * Called by Catalog Service to determine if a product has any active or pending deals.
     * Used to prevent product price changes or deletion.
     */
    @GetMapping("/product/{productId}/has-active-deals")
    public HasActiveDealsResponse hasActiveDeals(@PathVariable UUID productId) {
        boolean hasActive = dealService.hasActiveDeals(productId);
        return new HasActiveDealsResponse(productId, hasActive);
    }
}
