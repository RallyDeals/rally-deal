package com.groupdeal.dealservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupdeal.dealservice.client.CatalogClient;
import com.groupdeal.dealservice.client.InventoryClient;
import com.groupdeal.dealservice.client.dto.InventoryReservationResult;
import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.domain.DealOutbox;
import com.groupdeal.dealservice.domain.DealSlotRequest;
import com.groupdeal.dealservice.domain.DealStatus;
import com.rally.common.exceptions.domain.catalog.ProductNotFoundException;
import com.rally.common.exceptions.domain.deal.DealCancellationNotAllowedException;
import com.rally.common.exceptions.domain.deal.DealNotFoundException;
import com.rally.common.exceptions.domain.deal.InvalidDealConfigurationException;
import com.rally.common.exceptions.domain.inventory.InsufficientStockException;
import com.rally.common.exceptions.shared.UnauthorizedException;
import com.groupdeal.dealservice.repository.DealOutboxRepository;
import com.groupdeal.dealservice.repository.DealRepository;
import com.groupdeal.dealservice.repository.DealSlotRequestRepository;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.DealAnalyticsResponse;
import com.groupdeal.dealservice.web.dto.LeaveEligibilityResponse;
import com.groupdeal.dealservice.web.dto.SlotResponse;
import com.groupdeal.dealservice.web.dto.UpdateDealRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Covers all user stories DS-01 through DS-13 from the design doc.
 *
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

    // ── DS-01: Create deal (§5.1) ───────────────────────────────────────────────

    @Transactional
    public Deal createDeal(CreateDealRequest request, UUID sellerId) {
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
            throw new InsufficientStockException(request.productId(), request.dealStock(), 0);
        }

        Deal deal = new Deal();
        deal.setProductId(request.productId());
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

        // Outbox: deal.created (§6.3)
        writeOutbox(saved.getId(), "deal.created", buildDealCreatedPayload(saved));

        return saved;
    }

    // ── DS-01b: Update deal (only while PENDING + no participants) ───────────────

    @Transactional
    public Deal updateDeal(UUID dealId, UpdateDealRequest request, UUID sellerId) {
        Deal deal = getDeal(dealId);

        if (!deal.getSellerId().equals(sellerId)) {
            throw new UnauthorizedException("You are not the seller of deal '" + dealId + "'");
        }
        if (deal.getStatus() != DealStatus.PENDING) {
            throw new DealCancellationNotAllowedException(dealId);
        }
        if (deal.getCurrentParticipants() > 0) {
            throw new InvalidDealConfigurationException(
                    "Cannot update deal '" + dealId + "' — " + deal.getCurrentParticipants()
                            + " participant(s) already joined. You must cancel this deal and create a new one.");
        }

        if (request.minParticipants() > request.dealStock()) {
            throw new InvalidDealConfigurationException(
                    "min_participants (" + request.minParticipants() + ") cannot exceed deal_stock ("
                            + request.dealStock() + ")");
        }

        deal.setDealPrice(request.dealPrice());
        deal.setDealStock(request.dealStock());
        deal.setMinParticipants(request.minParticipants());
        deal.setDurationMinutes(request.durationMinutes());

        return dealRepository.save(deal);
    }

    // ── DS-02: Cancel deal (§5.3) ───────────────────────────────────────────────

    @Transactional
    public Deal cancelDeal(UUID dealId, UUID sellerId) {
        Deal deal = getDeal(dealId);

        if (!deal.getSellerId().equals(sellerId)) {
            throw new UnauthorizedException("You are not the seller of deal '" + dealId + "'");
        }
        if (deal.getStatus() != DealStatus.PENDING) {
            throw new DealCancellationNotAllowedException(dealId);
        }

        deal.setStatus(DealStatus.CANCELLED);
        Deal saved = dealRepository.save(deal);

        // Outbox: deal.cancelled (§6.3) — Inventory Service needs to release reserved
        // stock
        writeOutbox(saved.getId(), "deal.cancelled", buildDealCancelledPayload(saved));

        return saved;
    }

    // ── DS-03: Get deal detail (§5.2) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Deal getDeal(UUID dealId) {
        return dealRepository.findById(dealId)
                .orElseThrow(() -> new DealNotFoundException(dealId));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveDeals(UUID productId) {
        return dealRepository.existsByProductIdAndStatusIn(
                productId,
                List.of(DealStatus.PENDING, DealStatus.ACTIVE));
    }

    // ── DS-04 / DS-05: List deals with filtering (§4.1) ────────────────────────

    @Transactional(readOnly = true)
    public Page<Deal> findAll(String status, UUID sellerId, UUID productId, int page, int size) {
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

        return dealRepository.findAll(spec, PageRequest.of(page, size));
    }

    // ── DS-06: Reserve slot (§5.4) ──────────────────────────────────────────────

    @Transactional
    public SlotResponse reserveSlot(UUID dealId, UUID requestId) {
        // Idempotency check
        Optional<DealSlotRequest> existing = dealSlotRequestRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Deal deal = getDeal(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDeal(dealId);

        // Try first-join (pending→active) path
        if (deal.getStatus() == DealStatus.PENDING) {
            OffsetDateTime startTime = OffsetDateTime.now();
            OffsetDateTime endTime = startTime.plusMinutes(deal.getDurationMinutes());
            int rows = dealRepository.reserveSlotFirstJoin(dealId, startTime, endTime);
            if (rows > 0) {
                recordSlotRequest(requestId, dealId, "RESERVE", "SUCCESS");
                Deal updated = getDeal(dealId);
                return buildSlotSuccess(updated);
            }
        }

        // Try subsequent-join (already active) path
        if (deal.getStatus() == DealStatus.ACTIVE) {
            int rows = dealRepository.reserveSlotActive(dealId);
            if (rows > 0) {
                recordSlotRequest(requestId, dealId, "RESERVE", "SUCCESS");
                Deal updated = getDeal(dealId);
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
            Deal deal = getDeal(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDeal(dealId);
        if (deal.getCurrentParticipants() <= 0) {
            recordSlotRequest(requestId, dealId, "RELEASE", "REJECTED");
            return SlotResponse.rejected(dealId, "NO_SLOTS_TO_RELEASE");
        }

        int rows = dealRepository.releaseSlot(dealId);
        if (rows > 0) {
            recordSlotRequest(requestId, dealId, "RELEASE", "SUCCESS");
            return buildSlotSuccess(getDeal(dealId));
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
            Deal deal = getDeal(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDeal(dealId);
        if (deal.getAuthorizedCount() >= deal.getCurrentParticipants()) {
            recordSlotRequest(requestId, dealId, "AUTHORIZE", "REJECTED");
            return SlotResponse.rejected(dealId, "AUTHORIZED_COUNT_CAN'T_EXCEED_PARTICIPANTS");
        }

        int rows = dealRepository.authorizeSlot(dealId);
        if (rows > 0) {
            recordSlotRequest(requestId, dealId, "AUTHORIZE", "SUCCESS");

            // Check if this authorization fills all slots → succeed instantly (DS-08)
            int succeeded = dealRepository.succeedIfFullyAuthorized(dealId);
            Deal updated = getDeal(dealId);

            if (succeeded > 0) {
                log.info("Deal {} resolved as SUCCEEDED (stock filled via authorize-slot)", dealId);
                writeOutbox(updated.getId(), "deal.succeeded",
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
            Deal deal = getDeal(dealId);
            if ("SUCCESS".equals(existing.get().getResult())) {
                return buildSlotSuccess(deal);
            } else {
                return SlotResponse.rejected(dealId, "PREVIOUSLY_REJECTED");
            }
        }

        Deal deal = getDeal(dealId);
        if (deal.getAuthorizedCount() <= 0) {
            recordSlotRequest(requestId, dealId, "RELEASE_AUTHORIZED", "REJECTED");
            return SlotResponse.rejected(dealId, "NO_AUTHORIZED_SLOTS_TO_RELEASE");
        }

        int rows = dealRepository.releaseAuthorizedSlot(dealId);
        if (rows > 0) {
            recordSlotRequest(requestId, dealId, "RELEASE_AUTHORIZED", "SUCCESS");
            return buildSlotSuccess(getDeal(dealId));
        }

        recordSlotRequest(requestId, dealId, "RELEASE_AUTHORIZED", "REJECTED");
        return SlotResponse.rejected(dealId, "DEAL_NOT_ACTIVE_" + deal.getStatus().name());
    }

    // ── DS-13: Check leave eligibility (§5.8) ──────────────────────────────────
    // Read-only permission check — deal must be active and >10 min before end_time.

    @Transactional(readOnly = true)
    public LeaveEligibilityResponse checkLeaveEligible(UUID dealId) {
        Deal deal = getDeal(dealId);

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
                    Deal updated = getDeal(deal.getId());
                    log.info("Deal {} resolved as SUCCEEDED (timer expired, authorized_count={} >= min={})",
                            deal.getId(), updated.getAuthorizedCount(), updated.getMinParticipants());
                    writeOutbox(updated.getId(), "deal.succeeded",
                            buildDealResolvedPayload(updated, "TIMER_EXPIRED"));
                }
            } else {
                int rows = dealRepository.resolveExpiredAsFailed(deal.getId(), now);
                if (rows > 0) {
                    Deal updated = getDeal(deal.getId());
                    log.info("Deal {} resolved as FAILED (timer expired, authorized_count={} < min={})",
                            deal.getId(), updated.getAuthorizedCount(), updated.getMinParticipants());
                    writeOutbox(updated.getId(), "deal.failed",
                            buildDealFailedPayload(updated));
                }
            }
        }
    }

    // ── Analytics ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DealAnalyticsResponse getAnalytics(UUID sellerId) {
        long total;
        long active;
        long createdThisMonth;
        long createdToday;
        long completed;
        long succeeded;

        OffsetDateTime monthStart = YearMonth.now().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime dayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        if (sellerId != null) {
            total = dealRepository.countBySellerId(sellerId);
            active = dealRepository.countBySellerIdAndStatus(sellerId, DealStatus.ACTIVE);
            createdThisMonth = dealRepository.countBySellerIdAndCreatedAtBetween(sellerId, monthStart, OffsetDateTime.now());
            createdToday = dealRepository.countBySellerIdAndCreatedAtBetween(sellerId, dayStart, OffsetDateTime.now());
            completed = dealRepository.countBySellerIdAndStatusIn(sellerId, List.of(DealStatus.SUCCEEDED, DealStatus.FAILED));
            succeeded = dealRepository.countBySellerIdAndStatus(sellerId, DealStatus.SUCCEEDED);
        } else {
            total = dealRepository.count();
            active = dealRepository.countByStatus(DealStatus.ACTIVE);
            createdThisMonth = dealRepository.countByCreatedAtBetween(monthStart, OffsetDateTime.now());
            createdToday = dealRepository.countByCreatedAtBetween(dayStart, OffsetDateTime.now());
            completed = dealRepository.countByStatusIn(List.of(DealStatus.SUCCEEDED, DealStatus.FAILED));
            succeeded = dealRepository.countByStatus(DealStatus.SUCCEEDED);
        }

        BigDecimal successRate = completed > 0
                ? BigDecimal.valueOf(succeeded).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(completed), 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new DealAnalyticsResponse(total, createdThisMonth, active, createdToday, completed, successRate);
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

    // ── Event payload builders (snake_case per §6.3) ────────────────────────────

    private Map<String, Object> buildDealCreatedPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "deal.created");
        payload.put("occurred_at", OffsetDateTime.now().toString());
        payload.put("deal_id", deal.getId());
        payload.put("product_id", deal.getProductId());
        payload.put("seller_id", deal.getSellerId());
        payload.put("original_price", deal.getOriginalPrice());
        payload.put("deal_price", deal.getDealPrice());
        payload.put("deal_stock", deal.getDealStock());
        payload.put("min_participants", deal.getMinParticipants());
        payload.put("duration_minutes", deal.getDurationMinutes());
        return payload;
    }

    private Map<String, Object> buildDealCancelledPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "deal.cancelled");
        payload.put("occurred_at", OffsetDateTime.now().toString());
        payload.put("deal_id", deal.getId());
        payload.put("product_id", deal.getProductId());
        payload.put("deal_stock", deal.getDealStock());
        return payload;
    }

    private Map<String, Object> buildDealResolvedPayload(Deal deal, String resolvedTrigger) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "deal.succeeded");
        payload.put("occurred_at", OffsetDateTime.now().toString());
        payload.put("deal_id", deal.getId());
        payload.put("product_id", deal.getProductId());
        payload.put("seller_id", deal.getSellerId());
        payload.put("original_price", deal.getOriginalPrice());
        payload.put("deal_price", deal.getDealPrice());
        payload.put("current_participants", deal.getCurrentParticipants());
        payload.put("authorized_count", deal.getAuthorizedCount());
        payload.put("deal_stock", deal.getDealStock());
        payload.put("min_participants", deal.getMinParticipants());
        payload.put("start_time", deal.getStartTime() != null ? deal.getStartTime().toString() : null);
        payload.put("end_time", deal.getEndTime() != null ? deal.getEndTime().toString() : null);
        payload.put("resolved_trigger", resolvedTrigger);
        return payload;
    }

    private Map<String, Object> buildDealFailedPayload(Deal deal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "deal.failed");
        payload.put("occurred_at", OffsetDateTime.now().toString());
        payload.put("deal_id", deal.getId());
        payload.put("product_id", deal.getProductId());
        payload.put("seller_id", deal.getSellerId());
        payload.put("current_participants", deal.getCurrentParticipants());
        payload.put("authorized_count", deal.getAuthorizedCount());
        payload.put("deal_stock", deal.getDealStock());
        payload.put("min_participants", deal.getMinParticipants());
        payload.put("start_time", deal.getStartTime() != null ? deal.getStartTime().toString() : null);
        payload.put("end_time", deal.getEndTime() != null ? deal.getEndTime().toString() : null);
        payload.put("resolved_trigger", "TIMER_EXPIRED_BELOW_MIN");
        return payload;
    }
}
