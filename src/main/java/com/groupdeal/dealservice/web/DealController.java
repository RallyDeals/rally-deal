package com.groupdeal.dealservice.web;

import com.groupdeal.dealservice.domain.DealStatus;
import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;

    // ── Write endpoints ─────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<DealResponse> createDeal(
            @Valid @RequestBody CreateDealRequest request,
            @RequestHeader("X-User-Id") UUID sellerId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dealService.createDeal(request, sellerId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DealResponse> updateDeal(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDealRequest request,
            @RequestHeader("X-User-Id") UUID sellerId) {
        return ResponseEntity.status(HttpStatus.OK).body(dealService.updateDeal(id, request, sellerId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<DealResponse> cancelDeal(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID sellerId) {
        return ResponseEntity.ok(dealService.cancelDeal(id, sellerId));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<DealResponse>> getDealsBulk(@RequestBody BulkDealRequest request) {
        return ResponseEntity.ok(dealService.findAllByIdsAndStatus(request.ids(), request.statuses()));
    }

    // ── Read endpoints ──────────────────────────────────────────────────────────

    /**
     * GET /deals/analytics
     *
     * IMPORTANT: this route MUST be declared before GET /deals/{id} so that Spring
     * does not attempt to bind "analytics" as a UUID path variable.
     */
    @GetMapping("/analytics")
    public DealAnalyticsResponse getAnalytics(
            @RequestHeader(value = "X-User-Id", required = false) UUID sellerId) {
        return dealService.getAnalytics(sellerId);
    }

    /**
     * GET /deals — enriched paginated listing.
     *
     * All parameters are optional.
     *
     * @param search     filter by product name or seller name
     * @param categories list of category UUIDs
     * @param minPrice   minimum deal price (inclusive)
     * @param maxPrice   maximum deal price (inclusive)
     * @param sort       relevance | price-asc | price-desc | discount | ending-soon | most-joined | newest
     * @param sellerId   filter by seller UUID
     * @param status     comma-separated or repeated DealStatus values; defaults to ACTIVE,PENDING. Use ALL to include every status.
     * @param productId  product details page: shows deals for this product
     * @param page       0-based page index (default 0)
     * @param limit      page size (default 20)
     */
    @GetMapping
    public ResponseEntity<Page<DealOverview>> listDeals(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<UUID> categories,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) List<DealStatus> status,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(dealService.listDeals(
                search, categories, minPrice, maxPrice, sort,
                sellerId, status, productId, page, limit));
    }

    /**
     * GET /deals/{id} — enriched single deal with full product data from catalog.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DealDetails> getDeal(@PathVariable UUID id) {
        return ResponseEntity.ok(dealService.getDealDetails(id));
    }
}
