package com.groupdeal.dealservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupdeal.dealservice.client.CatalogClient;
import com.groupdeal.dealservice.client.InventoryClient;
import com.groupdeal.dealservice.client.dto.InventoryReservationResult;
import com.groupdeal.dealservice.client.dto.ProductDto;
import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.domain.DealStatus;
import com.groupdeal.dealservice.mapper.DealMapper;
import com.groupdeal.dealservice.repository.DealOutboxRepository;
import com.groupdeal.dealservice.repository.DealRepository;
import com.groupdeal.dealservice.repository.DealSlotRequestRepository;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.DealResponse;
import com.groupdeal.dealservice.web.dto.LeaveEligibilityResponse;
import com.rally.common.exceptions.domain.catalog.ProductNotFoundException;
import com.rally.common.exceptions.domain.deal.DealCancellationNotAllowedException;
import com.rally.common.exceptions.domain.deal.DealNotFoundException;
import com.rally.common.exceptions.domain.deal.InvalidDealConfigurationException;
import com.rally.common.exceptions.domain.inventory.InsufficientStockException;
import com.rally.common.exceptions.shared.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    @Mock private DealRepository dealRepository;
    @Mock private DealOutboxRepository dealOutboxRepository;
    @Mock private DealSlotRequestRepository dealSlotRequestRepository;
    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private DealMapper dealMapper;

    private DealService dealService;

    private static final UUID SELLER_ID   = UUID.randomUUID();
    private static final UUID PRODUCT_ID  = UUID.randomUUID();
    private static final BigDecimal BASE_PRICE = new BigDecimal("199.99");
    private static final BigDecimal DEAL_PRICE = new BigDecimal("149.99");

    @BeforeEach
    void setUp() {
        dealService = new DealService(
                dealRepository, dealOutboxRepository,
                dealSlotRequestRepository, catalogClient,
                inventoryClient, new ObjectMapper(), dealMapper);
    }

    // ── createDeal ───────────────────────────────────────────────────────────────

    @Test
    void createDeal_throwsWhen_minParticipantsExceedsDealStock() {
        CreateDealRequest req = new CreateDealRequest(PRODUCT_ID, DEAL_PRICE, 5, 10, 1440);

        assertThatThrownBy(() -> dealService.createDeal(req, SELLER_ID))
                .isInstanceOf(InvalidDealConfigurationException.class)
                .hasMessageContaining("min_participants");
    }

    @Test
    void createDeal_throwsWhen_productNotFound() {
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(Optional.empty());
        CreateDealRequest req = new CreateDealRequest(PRODUCT_ID, DEAL_PRICE, 100, 10, 1440);

        assertThatThrownBy(() -> dealService.createDeal(req, SELLER_ID))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void createDeal_throwsWhen_sellerMismatch() {
        UUID otherSeller = UUID.randomUUID();
        when(catalogClient.getProduct(PRODUCT_ID))
                .thenReturn(Optional.of(new ProductDto(PRODUCT_ID, otherSeller, BASE_PRICE)));
        CreateDealRequest req = new CreateDealRequest(PRODUCT_ID, DEAL_PRICE, 100, 10, 1440);

        assertThatThrownBy(() -> dealService.createDeal(req, SELLER_ID))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not the owner");
    }

    @Test
    void createDeal_throwsWhen_dealPriceNotLessThanBasePrice() {
        when(catalogClient.getProduct(PRODUCT_ID))
                .thenReturn(Optional.of(new ProductDto(PRODUCT_ID, SELLER_ID, BASE_PRICE)));
        // deal price == base price
        CreateDealRequest req = new CreateDealRequest(PRODUCT_ID, BASE_PRICE, 100, 10, 1440);

        assertThatThrownBy(() -> dealService.createDeal(req, SELLER_ID))
                .isInstanceOf(InvalidDealConfigurationException.class)
                .hasMessageContaining("base_price");
    }

    @Test
    void createDeal_throwsWhen_inventoryInsufficient() {
        when(catalogClient.getProduct(PRODUCT_ID))
                .thenReturn(Optional.of(new ProductDto(PRODUCT_ID, SELLER_ID, BASE_PRICE)));
        when(inventoryClient.reserve(PRODUCT_ID, 100))
                .thenReturn(new InventoryReservationResult(false, "INSUFFICIENT_STOCK"));
        CreateDealRequest req = new CreateDealRequest(PRODUCT_ID, DEAL_PRICE, 100, 10, 1440);

        assertThatThrownBy(() -> dealService.createDeal(req, SELLER_ID))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void createDeal_success_persistsDealAndOutbox() {
        when(catalogClient.getProduct(PRODUCT_ID))
                .thenReturn(Optional.of(new ProductDto(PRODUCT_ID, SELLER_ID, BASE_PRICE)));
        when(inventoryClient.reserve(PRODUCT_ID, 100))
                .thenReturn(new InventoryReservationResult(true, null));

        Deal saved = new Deal();
        saved.setProductId(PRODUCT_ID);
        saved.setSellerId(SELLER_ID);
        saved.setDealPrice(DEAL_PRICE);
        saved.setOriginalPrice(BASE_PRICE);
        saved.setDealStock(100);
        saved.setMinParticipants(10);
        saved.setStatus(DealStatus.PENDING);
        saved.setDurationMinutes(1440);
        saved.setCurrentParticipants(0);
        saved.setAuthorizedCount(0);
        when(dealRepository.save(any())).thenReturn(saved);

        DealResponse mappedResponse = new DealResponse(
                UUID.randomUUID(), PRODUCT_ID, SELLER_ID,
                BASE_PRICE, DEAL_PRICE, 100, 0, 0, 10,
                DealStatus.PENDING, null, 1440, null, null, null);
        when(dealMapper.toDealResponse(any(Deal.class))).thenReturn(mappedResponse);

        CreateDealRequest req = new CreateDealRequest(PRODUCT_ID, DEAL_PRICE, 100, 10, 1440);
        DealResponse result = dealService.createDeal(req, SELLER_ID);

        assertThat(result.status()).isEqualTo(DealStatus.PENDING);
        assertThat(result.currentParticipants()).isZero();
        verify(dealOutboxRepository).save(argThat(e -> "deal.created".equals(e.getEventType())));
    }

    // ── cancelDeal ───────────────────────────────────────────────────────────────

    @Test
    void cancelDeal_throwsWhen_notSeller() {
        Deal deal = pendingDeal();
        deal.setSellerId(UUID.randomUUID()); // different seller
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.cancelDeal(deal.getId(), SELLER_ID))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void cancelDeal_throwsWhen_dealNotPending() {
        Deal deal = pendingDeal();
        deal.setStatus(DealStatus.ACTIVE);
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.cancelDeal(deal.getId(), SELLER_ID))
                .isInstanceOf(DealCancellationNotAllowedException.class);
    }

    @Test
    void cancelDeal_success_setsStatusAndWritesOutbox() {
        Deal deal = pendingDeal();
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));
        when(dealRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DealResponse mappedResponse = new DealResponse(
                deal.getId(), PRODUCT_ID, SELLER_ID,
                BASE_PRICE, DEAL_PRICE, 100, 0, 0, 10,
                DealStatus.CANCELLED, null, 1440, null, null, null);
        when(dealMapper.toDealResponse(any(Deal.class))).thenReturn(mappedResponse);

        DealResponse result = dealService.cancelDeal(deal.getId(), SELLER_ID);

        assertThat(result.status()).isEqualTo(DealStatus.CANCELLED);
        verify(dealOutboxRepository).save(argThat(e -> "deal.cancelled".equals(e.getEventType())));
    }

    // ── getDeal ──────────────────────────────────────────────────────────────────

    @Test
    void getDeal_throwsWhen_notFound() {
        UUID unknownId = UUID.randomUUID();
        when(dealRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.getDeal(unknownId))
                .isInstanceOf(DealNotFoundException.class);
    }

    // ── checkLeaveEligible ───────────────────────────────────────────────────────

    @Test
    void checkLeaveEligible_notEligible_whenDealNotActive() {
        Deal deal = pendingDeal(); // PENDING
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        LeaveEligibilityResponse result = dealService.checkLeaveEligible(deal.getId());

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("DEAL_NOT_ACTIVE");
    }

    @Test
    void checkLeaveEligible_notEligible_whenTooCloseToEndTime() {
        Deal deal = pendingDeal();
        deal.setStatus(DealStatus.ACTIVE);
        deal.setEndTime(OffsetDateTime.now().plusMinutes(5)); // < 10 min away
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        LeaveEligibilityResponse result = dealService.checkLeaveEligible(deal.getId());

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("TOO_CLOSE_TO_END_TIME");
    }

    @Test
    void checkLeaveEligible_eligible_whenActiveAndTimeRemaining() {
        Deal deal = pendingDeal();
        deal.setStatus(DealStatus.ACTIVE);
        deal.setEndTime(OffsetDateTime.now().plusHours(2));
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        LeaveEligibilityResponse result = dealService.checkLeaveEligible(deal.getId());

        assertThat(result.eligible()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Deal pendingDeal() {
        Deal d = new Deal();
        d.setProductId(PRODUCT_ID);
        d.setSellerId(SELLER_ID);
        d.setStatus(DealStatus.PENDING);
        d.setDealPrice(DEAL_PRICE);
        d.setOriginalPrice(BASE_PRICE);
        d.setDealStock(100);
        d.setMinParticipants(10);
        d.setCurrentParticipants(0);
        d.setAuthorizedCount(0);
        d.setDurationMinutes(1440);
        // Set a deterministic ID so findById can be stubbed
        try {
            var f = Deal.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(d, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return d;
    }
}
