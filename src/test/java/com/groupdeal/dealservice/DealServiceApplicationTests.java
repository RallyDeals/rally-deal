package com.groupdeal.dealservice;

import com.groupdeal.dealservice.domain.DealStatus;
import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.DealResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import java.util.UUID;
import com.groupdeal.dealservice.client.CatalogClientStub;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step-1 smoke test: proves the scaffold actually boots and createDeal() round-trips
 * through a REAL Postgres (via Testcontainers + the Flyway migration), not a mock.
 *
 * This is intentionally the ONLY test in the scaffold. The concurrency-critical
 * reserve-slot/release-slot logic (step 4) needs its own dedicated Testcontainers
 * test that fires concurrent requests at a near-full deal — do not skip that one
 * when step 4 is built, it's the whole point of proving the guarded UPDATE works.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DealServiceApplicationTests {

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

    @Autowired
    private DealService dealService;

    @Test
    void contextLoads() {
    }

    @Test
    void createDeal_persistsWithCorrectDefaults() {
        UUID productId = UUID.randomUUID();
        CreateDealRequest request = new CreateDealRequest(
                productId,
                new BigDecimal("149.99"),
                100,
                40,
                1440
        );

        DealResponse deal = dealService.createDeal(request, CatalogClientStub.STUB_SELLER_ID);

        assertThat(deal.id()).isNotNull();
        assertThat(deal.status()).isEqualTo(DealStatus.PENDING);
        assertThat(deal.currentParticipants()).isZero();
        assertThat(deal.originalPrice()).isEqualByComparingTo("199.99"); // from CatalogClientStub
        assertThat(deal.startTime()).isNull();
        assertThat(deal.endTime()).isNull();
    }
}
