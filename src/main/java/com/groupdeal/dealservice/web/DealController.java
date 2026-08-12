package com.groupdeal.dealservice.web;

import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.DealResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * External, gateway-routed endpoints (design doc §4.1).
 *
 * sellerId is read from X-User-Id, the header the Gateway sets after
 * validating the caller's JWT — Deal Service does not validate the JWT itself.
 */
@RestController
@RequestMapping("/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;

    /** DS-01: Create a deal (§5.1). */
    @PostMapping
    public ResponseEntity<DealResponse> createDeal(
            @Valid @RequestBody CreateDealRequest request,
            @RequestHeader("X-User-Id") UUID sellerId) {
        Deal deal = dealService.createDeal(request, sellerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(DealResponse.from(deal));
    }

    /**
     * DS-03/DS-04/DS-05: Browse/filter deals (§4.1, §5.2).
     * Query params: status (comma-separated), sellerId, productId, page, size.
     * GET /deals?sellerId={id}&status=succeeded,failed covers DS-04 (seller outcome view).
     * GET /deals?status=... with no sellerId (admin role) covers DS-05.
     */
    @GetMapping
    public Page<DealResponse> listDeals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dealService.findAll(status, sellerId, productId, page, size)
                .map(DealResponse::from);
    }

    /** DS-03: Deal detail (§5.2). */
    @GetMapping("/{id}")
    public DealResponse getDeal(@PathVariable UUID id) {
        return DealResponse.from(dealService.getDeal(id));
    }

    /** DS-02: Cancel a deal (§5.3). */
    @PostMapping("/{id}/cancel")
    public DealResponse cancelDeal(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID sellerId) {
        return DealResponse.from(dealService.cancelDeal(id, sellerId));
    }
}
