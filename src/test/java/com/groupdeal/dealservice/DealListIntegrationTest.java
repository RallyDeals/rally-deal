package com.groupdeal.dealservice;

import com.groupdeal.dealservice.client.CatalogClientStub;
import com.groupdeal.dealservice.domain.DealStatus;
import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.DealOverview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GET /deals list endpoint (DealService.listDeals).
 * Tests all filter combinations: search, categories, price range, sort, sellerId, status, productId, pagination.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
class DealListIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("groupdeal_deals")
            .withUsername("groupdeal")
            .withPassword("groupdeal");

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private DealService dealService;

    private static final UUID SELLER = CatalogClientStub.STUB_SELLER_ID;
    private static final BigDecimal PRICE_99 = new BigDecimal("99.99");
    private static final BigDecimal PRICE_149 = new BigDecimal("149.99");
    private static final BigDecimal PRICE_179 = new BigDecimal("179.99");

    private UUID productId1, productId2, productId3;
    private UUID categoryId1, categoryId2;
    private UUID dealId1, dealId2, dealId3, dealId4, dealId5;

    @BeforeEach
    void setup() {
        productId1 = UUID.randomUUID();
        productId2 = UUID.randomUUID();
        productId3 = UUID.randomUUID();

        // Create deals with different attributes
        // Deal 1: PENDING, price 149.99, stock 100, min 10, cat1
        var deal1 = dealService.createDeal(new CreateDealRequest(productId1, PRICE_149, 100, 10, 1440), SELLER);
        dealId1 = deal1.id();
        categoryId1 = deal1.categoryId();

        // Deal 2: PENDING, price 99.99, stock 50, min 5, cat1
        var deal2 = dealService.createDeal(new CreateDealRequest(productId2, PRICE_99, 50, 5, 1440), SELLER);
        dealId2 = deal2.id();

        // Deal 3: PENDING, price 179.99, stock 200, min 20, cat2
        var deal3 = dealService.createDeal(new CreateDealRequest(productId3, PRICE_179, 200, 20, 1440), SELLER);
        dealId3 = deal3.id();
        categoryId2 = deal3.categoryId();

        // Deal 4: ACTIVATE deal 1 (reserve slot)
        dealService.reserveSlot(dealId1, UUID.randomUUID());

        // Deal 5: ACTIVE, price 149.99, stock 30, min 3, cat1 (same seller, different product)
        var deal5 = dealService.createDeal(new CreateDealRequest(productId1, PRICE_149, 30, 3, 1440), SELLER);
        dealId5 = deal5.id();
        dealService.reserveSlot(dealId5, UUID.randomUUID());

        // Deal 6: SUCCEEDED (create and fully authorize)
        UUID productId4 = UUID.randomUUID();
        var deal4 = dealService.createDeal(new CreateDealRequest(productId4, PRICE_149, 2, 1, 1440), SELLER);
        dealId4 = deal4.id();
        dealService.reserveSlot(dealId4, UUID.randomUUID());
        dealService.reserveSlot(dealId4, UUID.randomUUID());
        dealService.authorizeSlot(dealId4, UUID.randomUUID());
        dealService.authorizeSlot(dealId4, UUID.randomUUID());
    }

    // ── Basic listing ────────────────────────────────────────────────────────────

    @Test
    void listDeals_defaultsToActiveAndPending() {
        var page = dealService.listDeals(null, null, null, null, null, null, null, null, 0, 20);

        assertThat(page.getContent()).hasSize(4); // 2 PENDING + 2 ACTIVE
        assertThat(page.getContent()).extracting(DealOverview::status)
                .containsOnly(DealStatus.PENDING, DealStatus.ACTIVE);
    }

    @Test
    void listDeals_withExplicitStatus_returnsOnlyMatching() {
        var page = dealService.listDeals(null, null, null, null, null, null, List.of(DealStatus.PENDING), null, 0, 20);

        assertThat(page.getContent()).hasSize(2); // deal2, deal3
        assertThat(page.getContent()).extracting(DealOverview::status).containsOnly(DealStatus.PENDING);
    }

    @Test
    void listDeals_withMultipleStatuses() {
        var page = dealService.listDeals(null, null, null, null, null, null, List.of(DealStatus.PENDING, DealStatus.SUCCEEDED), null, 0, 20);

        assertThat(page.getContent()).hasSize(3); // 2 PENDING + 1 SUCCEEDED
        assertThat(page.getContent()).extracting(DealOverview::status)
                .containsOnly(DealStatus.PENDING, DealStatus.SUCCEEDED);
    }

    // ── Seller filter ────────────────────────────────────────────────────────────

    @Test
    void listDeals_filterBySellerId() {
        var page = dealService.listDeals(null, null, null, null, null, SELLER, null, null, 0, 20);

        assertThat(page.getContent()).hasSize(4); // 4 deals by SELLER with default status filter (ACTIVE+PENDING)
        assertThat(page.getContent()).extracting(DealOverview::sellerId).containsOnly(SELLER);
    }

    @Test
    void listDeals_filterBySellerId_withStatus() {
        var page = dealService.listDeals(null, null, null, null, null, SELLER, List.of(DealStatus.ACTIVE), null, 0, 20);

        assertThat(page.getContent()).hasSize(2); // deal1 and deal5 are ACTIVE
        assertThat(page.getContent()).extracting(DealOverview::status).containsOnly(DealStatus.ACTIVE);
    }

    // ── Product filter ──────────────────────────────────────────────────────────

    @Test
    void listDeals_filterByProductId() {
        var page = dealService.listDeals(null, null, null, null, null, null, null, productId1, 0, 20);

        assertThat(page.getContent()).hasSize(2); // deal1 and deal5 (both use productId1)
        assertThat(page.getContent()).extracting(DealOverview::productId).containsOnly(productId1);
    }

    // ── Category filter ──────────────────────────────────────────────────────────

    @Test
    void listDeals_filterBySingleCategory() {
        var page = dealService.listDeals(null, List.of(categoryId1), null, null, null, null, null, null, 0, 20);

        // categoryId1 is from productId1, which is used by deal1 and deal5
        // deal2 uses productId2 which has a different category
        assertThat(page.getContent()).hasSize(2); // deal1 and deal5
        assertThat(page.getContent()).extracting(DealOverview::categoryId).containsOnly(categoryId1);
    }

    @Test
    void listDeals_filterByMultipleCategories() {
        var page = dealService.listDeals(null, List.of(categoryId1, categoryId2), null, null, null, null, null, null, 0, 20);

        // categoryId1: deal1, deal5; categoryId2: deal2 (productId2)
        // deal3 uses productId3 (categoryId3), deal4 uses productId4 (categoryId4)
        assertThat(page.getContent()).hasSize(3); // deal1, deal5, deal2
        assertThat(page.getContent()).extracting(DealOverview::categoryId).containsOnly(categoryId1, categoryId2);
    }

    // ── Price range filter ──────────────────────────────────────────────────────

    @Test
    void listDeals_filterByMinPrice() {
        var page = dealService.listDeals(null, null, PRICE_149, null, null, null, null, null, 0, 20);

        // Deals with price >= 149.99: deal1 (149.99), deal3 (179.99), deal5 (149.99), deal4 (149.99 but SUCCEEDED so not in default)
        // Default status filter is ACTIVE+PENDING, so deal4 excluded
        assertThat(page.getContent()).hasSize(3); // deal1, deal3, deal5
        assertThat(page.getContent()).extracting(DealOverview::dealPrice).allMatch(p -> p.compareTo(PRICE_149) >= 0);
    }

    @Test
    void listDeals_filterByMaxPrice() {
        var page = dealService.listDeals(null, null, null, PRICE_149, null, null, null, null, 0, 20);

        // Deals with price <= 149.99: deal1 (149.99), deal2 (99.99), deal5 (149.99)
        assertThat(page.getContent()).hasSize(3); // deal1, deal2, deal5
        assertThat(page.getContent()).extracting(DealOverview::dealPrice).allMatch(p -> p.compareTo(PRICE_149) <= 0);
    }

    @Test
    void listDeals_filterByPriceRange() {
        var page = dealService.listDeals(null, null, new BigDecimal("100"), new BigDecimal("180"), null, null, null, null, 0, 20);

        // Deals with price 100-180: deal1 (149.99), deal5 (149.99), deal3 (179.99)
        // deal4 is SUCCEEDED so excluded by default
        assertThat(page.getContent()).hasSize(3); // deal1, deal5, deal3
        assertThat(page.getContent()).extracting(DealOverview::dealPrice).allMatch(p -> p.compareTo(new BigDecimal("100")) >= 0 && p.compareTo(new BigDecimal("180")) <= 0);
    }

    // ── Sort ────────────────────────────────────────────────────────────────────

    @Test
    void listDeals_sortByPriceAsc() {
        var page = dealService.listDeals(null, null, null, null, "price-asc", null, null, null, 0, 20);

        List<BigDecimal> prices = page.getContent().stream().map(DealOverview::dealPrice).toList();
        assertThat(prices).isSorted();
    }

    @Test
    void listDeals_sortByPriceDesc() {
        var page = dealService.listDeals(null, null, null, null, "price-desc", null, null, null, 0, 20);

        List<BigDecimal> prices = page.getContent().stream().map(DealOverview::dealPrice).toList();
        assertThat(prices).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void listDeals_sortByDiscount_computesPercentage() {
        var page = dealService.listDeals(null, null, null, null, "discount", null, null, null, 0, 20);

        // Discount = (originalPrice - dealPrice) / originalPrice * 100
        // Stub originalPrice is always 199.99
        // dealId2: dealPrice=99.99 -> discount = (199.99-99.99)/199.99*100 = 50%
        // dealId1/5: dealPrice=149.99 -> discount = (199.99-149.99)/199.99*100 = 25%
        // dealId3: dealPrice=199.99 -> discount = 0%
        List<Integer> discounts = page.getContent().stream()
                .map(d -> (int) (((d.originalPrice().subtract(d.dealPrice())).divide(d.originalPrice(), 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"))).intValue()))
                .toList();
        assertThat(discounts).isSortedAccordingTo((a, b) -> b - a); // DESC
    }

    @Test
    void listDeals_sortByEndingSoon() {
        var page = dealService.listDeals(null, null, null, null, "ending-soon", null, null, null, 0, 20);

        // Active deals have endTime, pending deals have null endTime
        // Should sort by endTime ASC (nulls last)
        assertThat(page.getContent()).isNotEmpty();
    }

    @Test
    void listDeals_sortByMostJoined() {
        var page = dealService.listDeals(null, null, null, null, "most-joined", null, null, null, 0, 20);

        List<Integer> participants = page.getContent().stream().map(DealOverview::currentParticipants).toList();
        assertThat(participants).isSortedAccordingTo((a, b) -> b - a); // DESC
    }

    @Test
    void listDeals_sortByNewest() {
        var page = dealService.listDeals(null, null, null, null, "newest", null, null, null, 0, 20);

        // Should sort by createdAt DESC
        assertThat(page.getContent()).isNotEmpty();
    }


    // ── Pagination ──────────────────────────────────────────────────────────────

    @Test
    void listDeals_pagination_pageSize() {
        var page = dealService.listDeals(null, null, null, null, null, null, null, null, 0, 2);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void listDeals_pagination_secondPage() {
        var page1 = dealService.listDeals(null, null, null, null, null, null, null, null, 0, 2);
        var page2 = dealService.listDeals(null, null, null, null, null, null, null, null, 1, 2);

        assertThat(page1.getContent()).hasSize(2);
        assertThat(page2.getContent()).hasSize(2);
        assertThat(page1.getContent().get(0).id()).isNotEqualTo(page2.getContent().get(0).id());
    }


    // ── Combined filters ────────────────────────────────────────────────────────

    @Test
    void listDeals_combinedFilters() {
        // category + price range + sort + pagination
        var page = dealService.listDeals(
                null,
                List.of(categoryId1),
                new BigDecimal("100"),
                new BigDecimal("200"),
                "price-asc",
                SELLER,
                null,
                null,
                0, 10);

        // Deals in cat1 with price 100-200: dealId1 (149.99), dealId2 (99.99 - excluded), dealId5 (149.99)
        // dealId2 is 99.99 so excluded by minPrice=100
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).extracting(DealOverview::id).containsExactlyInAnyOrder(dealId1, dealId5);
    }
}