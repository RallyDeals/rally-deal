package com.groupdeal.dealservice.web;

import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;

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

    @GetMapping("/analytics")
    public DealAnalyticsResponse getAnalytics(
            @RequestHeader(value = "X-User-Id", required = false) UUID sellerId) {
        return dealService.getAnalytics(sellerId);
    }

    @GetMapping
    public ResponseEntity<Page<DealResponse>> listDeals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(dealService.findAll(status, sellerId, productId, categoryId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DealResponse> getDeal(@PathVariable UUID id) {
        return ResponseEntity.ok(dealService.getDeal(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<DealResponse> cancelDeal(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID sellerId) {
        return ResponseEntity.ok(dealService.cancelDeal(id, sellerId));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<DealResponse>> getDealsBulk(@RequestBody BulkDealRequest request) {
        return ResponseEntity.ok(dealService.findAllByIdsAndStatus(request.ids(), request.statuses()));
    }
}
