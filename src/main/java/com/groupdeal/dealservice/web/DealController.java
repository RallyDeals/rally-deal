package com.groupdeal.dealservice.web;

import com.groupdeal.dealservice.client.CatalogClient;
import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.DealAnalyticsResponse;
import com.groupdeal.dealservice.web.dto.DealResponse;
import com.groupdeal.dealservice.web.dto.UpdateDealRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;
    private final CatalogClient catalogClient;

    @PostMapping
    public ResponseEntity<DealResponse> createDeal(
            @Valid @RequestBody CreateDealRequest request,
            @RequestHeader("X-User-Id") UUID sellerId) {
        Deal deal = dealService.createDeal(request, sellerId);
        ProductDto product = catalogClient.getProduct(request.productId()).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(DealResponse.from(deal, product));
    }

    @PatchMapping("/{id}")
    public DealResponse updateDeal(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDealRequest request,
            @RequestHeader("X-User-Id") UUID sellerId) {
        Deal deal = dealService.updateDeal(id, request, sellerId);
        ProductDto product = catalogClient.getProduct(deal.getProductId()).orElse(null);
        return DealResponse.from(deal, product);
    }

    @GetMapping("/analytics")
    public DealAnalyticsResponse getAnalytics(
            @RequestHeader(value = "X-User-Id", required = false) UUID sellerId) {
        return dealService.getAnalytics(sellerId);
    }

    @GetMapping
    public Page<DealResponse> listDeals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Deal> deals = dealService.findAll(status, sellerId, productId, page, size);

        Set<UUID> productIds = deals.getContent().stream()
                .map(Deal::getProductId)
                .collect(Collectors.toSet());
        Map<UUID, ProductDto> products = catalogClient.getProducts(productIds);

        return deals.map(deal -> DealResponse.from(deal, products));
    }

    @GetMapping("/{id}")
    public DealResponse getDeal(@PathVariable UUID id) {
        Deal deal = dealService.getDeal(id);
        ProductDto product = catalogClient.getProduct(deal.getProductId()).orElse(null);
        return DealResponse.from(deal, product);
    }

    @PostMapping("/{id}/cancel")
    public DealResponse cancelDeal(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID sellerId) {
        Deal deal = dealService.cancelDeal(id, sellerId);
        ProductDto product = catalogClient.getProduct(deal.getProductId()).orElse(null);
        return DealResponse.from(deal, product);
    }
}
