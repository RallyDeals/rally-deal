package com.groupdeal.dealservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupdeal.dealservice.client.CatalogClient;
import com.groupdeal.dealservice.client.InventoryClient;
import com.groupdeal.dealservice.client.dto.InventoryReservationResult;
import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.client.dto.ProductSummaryDto;
import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.domain.DealOutbox;
import com.groupdeal.dealservice.domain.DealSlotRequest;
import com.groupdeal.dealservice.domain.DealStatus;
import com.groupdeal.dealservice.mapper.DealMapper;
import com.groupdeal.dealservice.repository.DealOutboxRepository;
import com.groupdeal.dealservice.repository.DealRepository;
import com.groupdeal.dealservice.repository.DealSlotRequestRepository;
import com.groupdeal.dealservice.web.dto.*;
import com.rally.common.exceptions.domain.catalog.ProductNotFoundException;
import com.rally.common.exceptions.domain.deal.DealCancellationNotAllowedException;
import com.rally.common.exceptions.domain.deal.DealNotFoundException;
import com.rally.common.exceptions.domain.deal.InvalidDealConfigurationException;
import com.rally.common.exceptions.domain.inventory.InsufficientStockException;
import com.rally.common.exceptions.shared.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Covers all user stories DS-01 through DS-13 from the design doc.
 * <p>
 * Every slot operation is idempotent (via deal_slot_requests dedup table)
 * and writes outbox events in the same transaction as the state change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DealService {

    private final DealRepository dealRepository;
    private final DealOutboxRepository dealOutboxRepository;
    private final DealSlotRequestRepository dealSlotRequestRepository;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;
    private final DealMapper dealMapper;

    // ── DS-01: Create deal (§5.1) ───────────────────────────────────────────────

    @Transactional
    public DealResponse createDeal(CreateDealRequest request, UUID sellerId) {
        if (request.minParticipants() > request.dealStock()) {
            throw new InvalidDealConfigurationException(
                    "min_participants (" + request.minParticipants() + ") cannot exceed deal_stock ("
                            + request.dealStock() + ")");
        }

        ProductDto product = catalogClient.getProduct(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        if (!product.sellerId().equals(sellerId)) {
            throw new UnauthorizedException("You are not the owner of product '" + request.productId() + "'");
        }

        if (request.dealPrice().compareTo(product.basePrice()) >= 0) {
            throw new InvalidDealConfigurationException(
                    "deal_price must be strictly less than the product's base_price (" + product.basePrice() + ")");
        }

        InventoryReservationResult reservation = inventoryClient.reserve(request.productId(), request.dealStock());
        if (!reservation.success()) {
            int available = reservation.availableStock() != null ? reservation.availableStock() : 0;
            throw new InsufficientStockException(request.productId(), request.dealStock(), available);
        }

        Deal deal = new Deal();
        deal.setProductId(request.productId());
        deal.setCategoryId(product.categoryId());
        deal.setSellerId(sellerId);
        deal.setOriginalPrice(product.basePrice());
        deal.setDealPrice(request.dealPrice());
        deal.setDealStock(request.dealStock());
        deal.setCurrentParticipants(0);
        deal.setAuthorizedCount(0);
        deal.setMinParticipants(request.minParticipants());
        deal.setStatus(DealStatus.PENDING);
        deal.setDurationMinutes(request.durationMinutes());

        Deal saved = dealRepository.save(deal);

        // Outbox: Deal.Created (§6.3)
        writeOutbox(saved.getId(), "Deal.Created", buildDealCreatedPayload(saved));

        return dealMapper.toDealResponse(deal);
    }

    // ── DS-01b: Update deal (only while PENDING + no participants) ───────────────

    @Transactional
    public DealResponse updateDeal(UUID dealId, UpdateDealRequest request, UUID sellerId) {
        Deal deal = getDealById(dealId);

        if (!deal.getSellerId().equals(sellerId)) {
            throw new UnauthorizedException("You are not the seller of deal '" + dealId + "'");
        }

        if (deal.getStatus() != DealStatus.PENDING) {
            throw new InvalidDealConfigurationException(
                    "Cannot update deal '" + dealId + "' — It's already " + deal.getStatus());
        }

        if (request.minParticipants() > request.dealStock()) {
            throw new InvalidDealConfigurationException(
                    "min_participants (" + request.minParticipants() + ") cannot exceed deal_stock ("
                            + request.dealStock() + ")");
        }

        ProductDto product = catalogClient.getProduct(deal.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(deal.getProductId()));

        if (!product.sellerId().equals(sellerId)) {
            throw new UnauthorizedException("You are not the owner of product '" + deal.getProductId() + "'");
        }

        if (request.dealPrice().compareTo(product.basePrice()) >= 0) {
            throw new InvalidDealConfigurationException(
                    "deal_price must be strictly less than the product's base_price (" + product.basePrice() + ")");
        }

        int oldStock = deal.getDealStock();
        int newStock = request.dealStock();

        if (newStock > oldStock) {
            int delta = newStock - oldStock;
            InventoryReservationResult reservation = inventoryClient.reserve(deal.getProductId(), delta);
            if (!reservation.success()) {
                int available = reservation.availableStock() != null ? reservation.availableStock() : 0;
                throw new InsufficientStockException(deal.getProductId(), delta, available);
            }
        } else if (newStock < oldStock) {
            int delta = oldStock - newStock;
            inventoryClient.release(deal.getProductId(), delta);
        }

        deal.setOriginalPrice(product.basePrice());
        if (product.categoryId() != null) {
            deal.setCategoryId(product.categoryId());
        }

        deal.setDealPrice(request.dealPrice());
        deal.setDealStock(newStock);
        deal.setMinParticipants(request.minParticipants());
        deal.setDurationMinutes(request.durationMinutes());

        Deal updatedDeal = dealRepository.save(deal);

        return dealMapper.toDealResponse(updatedDeal);
    }

    // ── DS-02: Cancel deal (§5.3) ───────────────────────────────────────────────

    @Transactional
    public DealResponse cancelDeal(UUID dealId, UUID sellerId) {
        Deal deal = getDealById(dealId);

        if (!deal.getSellerId().equals(sellerId)) {
            throw new UnauthorizedException("You are not the seller of deal '" + dealId + "'");
        }
        if (deal.getStatus() != DealStatus.PENDING) {
            // TODO: Consider idempotency here — if status is already CANCELLED from a retried request, return success (200) instead of throwing DealCancellationNotAllowedException
            throw new DealCancellationNotAllowedException(dealId);
        }

        deal.setStatus(DealStatus.CANCELLED);
        Deal saved = dealRepository.save(deal);

        // Outbox: Deal.Cancelled (§6.3) — Inventory Service needs to release reserved stock
        writeOutbox(saved.getId(), "Deal.Cancelled", buildDealCancelledPayload(saved));

        return dealMapper.toDealResponse(saved);
    }

    // ── DS-03: Get deal detail (§5.2) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public DealResponse getDeal(UUID dealId) {
        return dealMapper.toDealResponse(
                dealRepository.findById(dealId)
                        .orElseThrow(() -> new DealNotFoundException(dealId))
        );
    }

    /**
     * Enriched single-deal detail — DealOverview fields + product description and images.
     * Product data comes from CatalogClient.getProduct() (single-product endpoint).
     * If catalog is unavailable, product fields are returned as null.
     */
    @Transactional(readOnly = true)
    public DealDetails getDealDetails(UUID dealId) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new DealNotFoundException(dealId));

        ProductDto product = null;
        try {
            product = catalogClient.getProduct(deal.getProductId()).orElse(null);
        } catch (Exception e) {
            log.warn("Catalog unavailable for deal {} product enrichment — returning null product fields", dealId, e);
        }

        return toDealDetails(deal, product);
    }

    @Transactional(readOnly = true)
    public List<DealResponse> findAllByIdsAndStatus(List<UUID> ids, List<DealStatus> status) {
        List<Deal> deals = (status == null || status.isEmpty())
                ? dealRepository.findAllById(ids)
                : dealRepository.findDealsByIdAndStatusIn(ids, status);

        return deals.stream()
                .map(dealMapper::toDealResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasActiveDeals(UUID productId) {
        return dealRepository.existsByProductIdAndStatusIn(
                productId,
                List.of(DealStatus.PENDING, DealStatus.ACTIVE));
    }

    // ── DS-04 / DS-05: List deals with filtering + enrichment (§4.1) ───────────

    /**
     * Legacy overloads — preserved for internal callers (tests, bulk endpoint, etc.).
     * The public-facing HTTP endpoint now goes through {@link #listDeals}.
     */
    @Transactional(readOnly = true)
    public Page<DealResponse> findAll(String status, UUID sellerId, UUID productId, int page, int size) {
        return findAll(status, sellerId, productId, null, page, size);
    }

    @Transactional(readOnly = true)
    public Page<DealResponse> findAll(String status, UUID sellerId, UUID productId, UUID categoryId, int page, int size) {
        Specification<Deal> spec = Specification.where(null);

        if (status != null && !status.isBlank()) {
            // Support comma-separated statuses like "succeeded,failed"
            String[] statuses = status.split(",");
            List<DealStatus> statusList = Arrays.stream(statuses)
                    .map(s -> DealStatus.valueOf(s.trim().toUpperCase()))
                    .toList();
            spec = spec.and((root, query, cb) -> root.get("status").in(statusList));
        }
        if (sellerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sellerId"), sellerId));
        }
        if (productId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("productId"), productId));
        }
        if (categoryId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("categoryId"), categoryId));
        }

        return dealRepository.findAll(spec, PageRequest.of(page, size)).map(dealMapper::toDealResponse);
    }

    /**
     * Enriched deal listing supporting all query params from the spec.
     *
     * <p>Sorting, filtering, pagination are applied at the DB layer via Specifications.
     * Product enrichment is done in a single bulk call to the catalog service.
     * If catalog is unavailable the deals are returned with null product fields.
     *
     * @param search        filter by product name or seller name (client-side post-filter after catalog call for stub; DB-side where possible)
     * @param categories    filter by one or more category UUIDs
     * @param minPrice      minimum deal price (inclusive)
     * @param maxPrice      maximum deal price (inclusive)
     * @param sort          sort order: relevance|price-asc|price-desc|discount|ending-soon|most-joined|newest
     * @param sellerId      filter by seller
     * @param statuses      filter by status list; defaults to [ACTIVE, PENDING] when null/empty
     * @param userId        future: filter deals the buyer has joined (not yet implemented — requires participation table)
     * @param productId     filter by product
     * @param page          0-based page index
     * @param limit         page size
     */
    @Transactional(readOnly = true)
    public Page<DealOverview> listDeals(
            String search,
            List<UUID> categories,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String sort,
            UUID sellerId,
            List<DealStatus> statuses,
            UUID userId,
            UUID productId,
            int page,
            int limit) {

        Specification<Deal> spec = Specification.where(null);

        // ── status filter — default to ACTIVE + PENDING when omitted ──────────────
        List<DealStatus> effectiveStatuses = (statuses != null && !statuses.isEmpty())
                ? statuses
                : List.of(DealStatus.ACTIVE, DealStatus.PENDING);
        spec = spec.and((root, q, cb) -> root.get("status").in(effectiveStatuses));

        // ── scalar equality filters ───────────────────────────────────────────────
        if (sellerId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("sellerId"), sellerId));
        }
        if (productId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("productId"), productId));
        }

        // ── category list filter ──────────────────────────────────────────────────
        if (categories != null && !categories.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("categoryId").in(categories));
        }

        // ── price range ───────────────────────────────────────────────────────────
        if (minPrice != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("dealPrice"), minPrice));
        }
        if (maxPrice != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("dealPrice"), maxPrice));
        }

        // ── sorting ────────────────────────────────────────────────────────────────
        Sort dbSort = resolveSort(sort);
        PageRequest pageRequest = PageRequest.of(page, limit, dbSort);

        Page<Deal> dealPage;
        boolean isDiscountSort = "discount".equalsIgnoreCase(sort);
        if (isDiscountSort) {
            // Use native query with computed discount percentage for proper sorting
            // For category filter, use first category if multiple provided (simplification for native query)
            UUID categoryForDiscountSort = (categories != null && !categories.isEmpty()) ? categories.get(0) : null;
            // Convert statuses to strings for native query (stored as VARCHAR in DB)
            List<String> statusStrings = effectiveStatuses.stream().map(Enum::name).toList();
            int offset = page * limit;
            List<Deal> deals = dealRepository.findAllWithDiscountSort(
                    statusStrings, sellerId, productId, categoryForDiscountSort, minPrice, maxPrice, limit, offset);
            long total = dealRepository.countWithDiscountSortFilters(
                    statusStrings, sellerId, productId, categoryForDiscountSort, minPrice, maxPrice);
            dealPage = new PageImpl<>(deals, pageRequest, total);
        } else {
            dealPage = dealRepository.findAll(spec, pageRequest);
        }

        // ── bulk catalog enrichment ───────────────────────────────────────────────
        List<UUID> productIds = dealPage.getContent().stream()
                .map(Deal::getProductId)
                .distinct()
                .collect(Collectors.toList());

        Map<UUID, ProductSummaryDto> summaries;
        try {
            summaries = catalogClient.getProductSummaries(productIds);
        } catch (Exception e) {
            log.warn("Catalog Service unavailable for bulk product summary — returning null product fields", e);
            summaries = Collections.emptyMap();
        }

        final Map<UUID, ProductSummaryDto> summariesFinal = summaries;

        // ── map to DealOverview + optional search post-filter ─────────────────────
        // NOTE: search filtering is applied after enrichment because the product name
        // and seller name are not stored in the deals table. For production scale this
        // should be handled by a search service or denormalised columns.
        Page<DealOverview> overviewPage = dealPage.map(deal -> {
            ProductSummaryDto summary = summariesFinal.get(deal.getProductId());
            return toDealOverview(deal, summary);
        });

        // Post-filter by `search` if provided and catalog enrichment succeeded
        if (search != null && !search.isBlank() && !summariesFinal.isEmpty()) {
            String lc = search.toLowerCase();
            List<DealOverview> filtered = overviewPage.getContent().stream()
                    .filter(o -> {
                        boolean matchName = o.productName() != null && o.productName().toLowerCase().contains(lc);
                        boolean matchSeller = o.sellerName() != null && o.sellerName().toLowerCase().contains(lc);
                        return matchName || matchSeller;
                    })
                    .collect(Collectors.toList());
            // Wrap in a new Page preserving the original pagination metadata
            return new PageImpl<>(filtered, pageRequest, overviewPage.getTotalElements());
        }

        return overviewPage;
    }

    // ── Sort resolution ──────────────────────────────────────────────────────────

    private Sort resolveSort(String sort) {
        if (sort == null) return Sort.by(Sort.Direction.DESC, "createdAt");
        return switch (sort.toLowerCase()) {
            case "price-asc"    -> Sort.by(Sort.Direction.ASC,  "dealPrice");
            case "price-desc"   -> Sort.by(Sort.Direction.DESC, "dealPrice");
            case "ending-soon"  -> Sort.by(Sort.Direction.ASC,  "endTime");
            case "most-joined"  -> Sort.by(Sort.Direction.DESC, "currentParticipants");
            case "newest"       -> Sort.by(Sort.Direction.DESC, "createdAt");
            default             -> Sort.by(Sort.Direction.DESC, "createdAt"); // relevance, discount & unknown
        };
    }

    // ── DS-06: Reserve slot (§5.4) ──────────────────────────────────────────────

    @Transactional
    public SlotResponse reserveSlot(UUID dealId, UUID requestId) {
        // Idempotency check
        Optional<DealSlotRequest> existing = dealSlotRequestRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Deal deal = getDealById(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDealById(dealId);

        // Try first-join (pending→active) path
        if (deal.getStatus() == DealStatus.PENDING) {
            OffsetDateTime startTime = OffsetDateTime.now();
            OffsetDateTime endTime = startTime.plusMinutes(deal.getDurationMinutes());
            int rows = dealRepository.reserveSlotFirstJoin(dealId, startTime, endTime);
            if (rows > 0) {
                recordSlotRequest(requestId, dealId, "RESERVE", "SUCCESS");
                Deal updated = getDealById(dealId);
                // Outbox: Deal.Activated — catalog needs PENDING→ACTIVE transition
                writeOutbox(updated.getId(), "Deal.Activated", buildDealActivatedPayload(updated));
                return buildSlotSuccess(updated);
            }
        }

        // Try subsequent-join (already active) path
        if (deal.getStatus() == DealStatus.ACTIVE) {
            int rows = dealRepository.reserveSlotActive(dealId);
            if (rows > 0) {
                recordSlotRequest(requestId, dealId, "RESERVE", "SUCCESS");
                Deal updated = getDealById(dealId);
                return buildSlotSuccess(updated);
            }
        }

        // Rejected — either full or not joinable
        String reason;
        if (deal.getStatus() == DealStatus.ACTIVE && deal.getCurrentParticipants() >= deal.getDealStock()) {
            reason = "DEAL_FULL";
        } else {
            reason = "DEAL_NOT_ACTIVE_" + deal.getStatus().name();
        }
        recordSlotRequest(requestId, dealId, "RESERVE", "REJECTED");
        return SlotResponse.rejected(dealId, reason);
    }

    // ── DS-07: Release slot (§5.5) ──────────────────────────────────────────────
    // Payment declined before authorization — decrements current_participants only.

    @Transactional
    public SlotResponse releaseSlot(UUID dealId, UUID requestId) {
        Optional<DealSlotRequest> existing = dealSlotRequestRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Deal deal = getDealById(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDealById(dealId);
        if (deal.getCurrentParticipants() <= 0) {
            recordSlotRequest(requestId, dealId, "RELEASE", "REJECTED");
            return SlotResponse.rejected(dealId, "NO_SLOTS_TO_RELEASE");
        }

        int rows = dealRepository.releaseSlot(dealId);
        if (rows > 0) {
            recordSlotRequest(requestId, dealId, "RELEASE", "SUCCESS");
            return buildSlotSuccess(getDealById(dealId));
        }

        recordSlotRequest(requestId, dealId, "RELEASE", "REJECTED");
        return SlotResponse.rejected(dealId, "DEAL_NOT_ACTIVE_" + deal.getStatus().name());
    }

    // ── DS-11: Authorize slot (§5.6) ────────────────────────────────────────────
    // Marks a reserved slot as payment-authorized. May flip active→succeeded.

    @Transactional
    public SlotResponse authorizeSlot(UUID dealId, UUID requestId) {
        Optional<DealSlotRequest> existing = dealSlotRequestRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Deal deal = getDealById(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDealById(dealId);
        if (deal.getAuthorizedCount() >= deal.getCurrentParticipants()) {
            recordSlotRequest(requestId, dealId, "AUTHORIZE", "REJECTED");
            return SlotResponse.rejected(dealId, "AUTHORIZED_COUNT_CAN'T_EXCEED_PARTICIPANTS");
        }

        int rows = dealRepository.authorizeSlot(dealId);
        if (rows > 0) {
            recordSlotRequest(requestId, dealId, "AUTHORIZE", "SUCCESS");

            // Check if this authorization fills all slots → succeed instantly (DS-08)
            int succeeded = dealRepository.succeedIfFullyAuthorized(dealId);
            Deal updated = getDealById(dealId);

            if (succeeded > 0) {
                log.info("Deal {} resolved as SUCCEEDED (stock filled via authorize-slot)", dealId);
                writeOutbox(updated.getId(), "Deal.Succeeded",
                        buildDealResolvedPayload(updated, "STOCK_FILLED"));
            }

            return buildSlotSuccess(updated);
        }

        recordSlotRequest(requestId, dealId, "AUTHORIZE", "REJECTED");
        return SlotResponse.rejected(dealId, "DEAL_NOT_ACTIVE_" + deal.getStatus().name());
    }

    // ── DS-12: Release authorized slot (§5.7) ──────────────────────────────────
    // Participant left after payment was authorized — decrements both counters.

    @Transactional
    public SlotResponse releaseAuthorizedSlot(UUID dealId, UUID requestId) {
        Optional<DealSlotRequest> existing = dealSlotRequestRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Deal deal = getDealById(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDealById(dealId);
        if (deal.getAuthorizedCount() <= 0) {
            recordSlotRequest(requestId, dealId, "RELEASE_AUTHORIZED", "REJECTED");
            return SlotResponse.rejected(dealId, "NO_AUTHORIZED_SLOTS_TO_RELEASE");
        }

        int rows = dealRepository.releaseAuthorizedSlot(dealId);
        if (rows > 0) {
            recordSlotRequest(requestId, dealId, "RELEASE_AUTHORIZED", "SUCCESS");
            return buildSlotSuccess(getDealById(dealId));
        }

        recordSlotRequest(requestId, dealId, "RELEASE_AUTHORIZED", "REJECTED");
        return SlotResponse.rejected(dealId, "DEAL_NOT_ACTIVE_" + deal.getStatus().name());
    }

    // ── DS-13: Check leave eligibility (§5.8) ──────────────────────────────────
    // Read-only permission check — deal must be active and >10 min before end_time.

    @Transactional(readOnly = true)
    public LeaveEligibilityResponse checkLeaveEligible(UUID dealId) {
        Deal deal = getDealById(dealId);

        if (deal.getStatus() != DealStatus.ACTIVE) {
            return LeaveEligibilityResponse.notEligible(dealId, "DEAL_NOT_ACTIVE");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (deal.getEndTime() != null && deal.getEndTime().minusMinutes(10).isBefore(now)) {
            return LeaveEligibilityResponse.notEligible(dealId, "TOO_CLOSE_TO_END_TIME");
        }

        return LeaveEligibilityResponse.eligible(dealId);
    }

    // ── DS-09: Timer-based resolution (§7) ─────────────────────────────────────
    // Called by DealResolutionScheduler.

    @Transactional
    public void resolveExpiredDeals() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Deal> expired = dealRepository.findExpiredActiveDeals(now);

        for (Deal deal : expired) {
            if (deal.getAuthorizedCount() >= deal.getMinParticipants()) {
                int rows = dealRepository.resolveExpiredAsSucceeded(deal.getId(), now);
                if (rows > 0) {
                    Deal updated = getDealById(deal.getId());
                    log.info("Deal {} resolved as SUCCEEDED (timer expired, authorized_count={} >= min={})",
                            deal.getId(), updated.getAuthorizedCount(), updated.getMinParticipants());
                    writeOutbox(updated.getId(), "Deal.Succeeded",
                            buildDealResolvedPayload(updated, "TIMER_EXPIRED"));
                }
            } else {
                int rows = dealRepository.resolveExpiredAsFailed(deal.getId(), now);
                if (rows > 0) {
                    Deal updated = getDealById(deal.getId());
                    log.info("Deal {} resolved as FAILED (timer expired, authorized_count={} < min={})",
                            deal.getId(), updated.getAuthorizedCount(), updated.getMinParticipants());
                    writeOutbox(updated.getId(), "Deal.Failed",
                            buildDealFailedPayload(updated));
                }
            }
        }
    }

    // ── Analytics ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DealAnalyticsResponse getAnalytics(UUID sellerId) {
        long total;
        long activeDeals;  // ACTIVE + PENDING per spec
        long createdThisMonth;
        long createdToday;
        long completedDeals;    // SUCCEEDED only per spec
        long succeeded;
        long failed;

        OffsetDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime dayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        List<DealStatus> activeStatuses = List.of(DealStatus.ACTIVE, DealStatus.PENDING);

        if (sellerId != null) {
            total             = dealRepository.countBySellerId(sellerId);
            activeDeals       = dealRepository.countBySellerIdAndStatusIn(sellerId, activeStatuses);
            createdThisMonth  = dealRepository.countBySellerIdAndCreatedAtBetween(sellerId, monthStart, OffsetDateTime.now());
            createdToday      = dealRepository.countBySellerIdAndCreatedAtBetween(sellerId, dayStart, OffsetDateTime.now());
            completedDeals    = dealRepository.countBySellerIdAndStatus(sellerId, DealStatus.SUCCEEDED);
            failed            = dealRepository.countBySellerIdAndStatus(sellerId, DealStatus.FAILED);
            succeeded         = completedDeals;
        } else {
            total             = dealRepository.count();
            activeDeals       = dealRepository.countByStatusIn(activeStatuses);
            createdThisMonth  = dealRepository.countByCreatedAtBetween(monthStart, OffsetDateTime.now());
            createdToday      = dealRepository.countByCreatedAtBetween(dayStart, OffsetDateTime.now());
            completedDeals    = dealRepository.countByStatus(DealStatus.SUCCEEDED);
            failed            = dealRepository.countByStatus(DealStatus.FAILED);
            succeeded         = completedDeals;
        }

        double successRate = (completedDeals + failed) > 0
                ? (double) succeeded / (completedDeals + failed) * 100.0
                : 0.0;

        return new DealAnalyticsResponse(total, createdThisMonth, activeDeals, createdToday, completedDeals, successRate);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void recordSlotRequest(UUID requestId, UUID dealId, String operation, String result) {
        dealSlotRequestRepository.save(new DealSlotRequest(requestId, dealId, operation, result));
    }

    private SlotResponse buildSlotSuccess(Deal deal) {
        return SlotResponse.success(
                deal.getId(),
                deal.getDealPrice(),
                deal.getCurrentParticipants(),
                deal.getAuthorizedCount(),
                deal.getDealStock(),
                deal.getStatus(),
                deal.getStartTime(),
                deal.getEndTime());
    }

    private void writeOutbox(UUID dealId, String eventType, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            dealOutboxRepository.save(new DealOutbox(dealId, eventType, json));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload for deal " + dealId, e);
        }
    }

    private Deal getDealById(UUID id){
        return dealRepository.findById(id).orElseThrow(() -> new DealNotFoundException(id));
    }

    // ── DTO assembly ─────────────────────────────────────────────────────────────

    private static int computeNeeded(Deal d) {
        return Math.max(0, d.getMinParticipants() - d.getCurrentParticipants());
    }

    private static int computeProgress(Deal d) {
        if (d.getDealStock() <= 0) return 0;
        int pct = (d.getCurrentParticipants() * 100) / d.getDealStock();
        return Math.min(100, pct);
    }

    private static long computeTimeRemaining(Deal d) {
        if (d.getEndTime() == null) return 0L;
        long secs = java.time.Duration.between(OffsetDateTime.now(), d.getEndTime()).getSeconds();
        return Math.max(0L, secs);
    }

    private DealOverview toDealOverview(Deal deal, ProductSummaryDto summary) {
        return new DealOverview(
                deal.getId(),
                deal.getProductId(),
                deal.getSellerId(),
                deal.getCategoryId(),
                deal.getOriginalPrice(),
                deal.getDealPrice(),
                deal.getDealStock(),
                deal.getCurrentParticipants(),
                deal.getMinParticipants(),
                deal.getAuthorizedCount(),
                deal.getStatus(),
                deal.getDurationMinutes(),
                deal.getStartTime(),
                deal.getEndTime(),
                deal.getCreatedAt(),
                computeNeeded(deal),
                computeProgress(deal),
                computeTimeRemaining(deal),
                summary != null ? summary.name()       : null,
                summary != null ? summary.imageUrl()   : null,
                summary != null ? summary.category()   : null,
                summary != null ? summary.sku()        : null,
                summary != null ? summary.sellerName() : null
        );
    }

    private DealDetails toDealDetails(Deal deal, ProductDto product) {
        return new DealDetails(
                deal.getId(),
                deal.getProductId(),
                deal.getSellerId(),
                deal.getCategoryId(),
                deal.getOriginalPrice(),
                deal.getDealPrice(),
                deal.getDealStock(),
                deal.getCurrentParticipants(),
                deal.getMinParticipants(),
                deal.getAuthorizedCount(),
                deal.getStatus(),
                deal.getDurationMinutes(),
                deal.getStartTime(),
                deal.getEndTime(),
                deal.getCreatedAt(),
                computeNeeded(deal),
                computeProgress(deal),
                computeTimeRemaining(deal),
                product != null ? product.name()        : null,
                product != null ? product.imageUrl()    : null,
                product != null ? product.category()    : null,
                product != null ? product.sku()         : null,
                product != null ? product.sellerName()  : null,
                product != null ? product.description() : null,
                product != null ? product.images()      : null
        );
    }

    // ── Event payload builders ──────────────────────────────────────────────────

    private Map<String, Object> buildDealCreatedPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // payload.put("eventId", UUID.randomUUID().toString()); // in header
        // payload.put("eventType", "Deal.Created"); // in header
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("dealId", deal.getId());
        payload.put("productId", deal.getProductId());
        payload.put("categoryId", deal.getCategoryId());
        payload.put("sellerId", deal.getSellerId());
        payload.put("originalPrice", deal.getOriginalPrice());
        payload.put("dealPrice", deal.getDealPrice());
        payload.put("dealStock", deal.getDealStock());
        payload.put("minParticipants", deal.getMinParticipants());
        payload.put("durationMinutes", deal.getDurationMinutes());
        return payload;
    }

    private Map<String, Object> buildDealActivatedPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // payload.put("eventId", UUID.randomUUID().toString()); // in header
        // payload.put("eventType", "Deal.Activated"); // in header
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("dealId", deal.getId());
        payload.put("productId", deal.getProductId());
        payload.put("categoryId", deal.getCategoryId());
        payload.put("dealPrice", deal.getDealPrice());
        payload.put("dealStock", deal.getDealStock());
        payload.put("currentParticipants", deal.getCurrentParticipants());
        payload.put("minParticipants", deal.getMinParticipants());
        payload.put("durationMinutes", deal.getDurationMinutes());
        payload.put("startTime", deal.getStartTime() != null ? deal.getStartTime().toString() : null);
        payload.put("endTime", deal.getEndTime() != null ? deal.getEndTime().toString() : null);
        return payload;
    }

    private Map<String, Object> buildDealCancelledPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // payload.put("eventId", UUID.randomUUID().toString()); // in header
        // payload.put("eventType", "Deal.Cancelled"); // in header
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("dealId", deal.getId());
        payload.put("productId", deal.getProductId());
        payload.put("reservedStock", deal.getDealStock());
        payload.put("authorizedCount", deal.getAuthorizedCount());
        payload.put("quantity", deal.getDealStock()); // needed for inventory service
        return payload;
    }

    private Map<String, Object> buildDealResolvedPayload(Deal deal, String resolvedTrigger) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // payload.put("eventId", UUID.randomUUID().toString()); // in header
        // payload.put("eventType", "Deal.Succeeded"); // in header
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("dealId", deal.getId());
        payload.put("reservedStock", deal.getDealStock());
        payload.put("authorizedCount", deal.getAuthorizedCount());
        payload.put("productId", deal.getProductId());
        payload.put("quantity", deal.getDealStock() - deal.getAuthorizedCount()); // needed for inventory service
        return payload;
    }

    private Map<String, Object> buildDealFailedPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // payload.put("eventId", UUID.randomUUID().toString()); // in header
        // payload.put("eventType", "Deal.Failed"); // in header
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("dealId", deal.getId());
        payload.put("reservedStock", deal.getDealStock());
        payload.put("authorizedCount", deal.getAuthorizedCount());
        payload.put("productId", deal.getProductId());
        payload.put("quantity", deal.getDealStock()); // needed for inventory service
        return payload;
    }
}
