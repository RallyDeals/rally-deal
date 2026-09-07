package com.groupdeal.dealservice;

import com.groupdeal.dealservice.client.CatalogClientStub;
import com.groupdeal.dealservice.service.DealService;
import com.groupdeal.dealservice.web.dto.CreateDealRequest;
import com.groupdeal.dealservice.web.dto.SlotResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 3 — Concurrency test.
 *
 * This is the test referenced in the original DealServiceApplicationTests comment:
 * "The concurrency-critical reserve-slot/release-slot logic needs its own dedicated
 * Testcontainers test that fires concurrent requests at a near-full deal".
 *
 * Proves the guarded UPDATE (WHERE current_participants < deal_stock) is race-condition-proof.
 * No amount of mocking can validate this; only real concurrent DB writes can.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DealReserveSlotConcurrencyTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
			.withStartupTimeout(Duration.ofMinutes(3))
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

    @Test
    void reserveSlot_neverExceedsDealStock_underHighConcurrency() throws Exception {
        final int STOCK   = 10;
        final int THREADS = 20;

        // Create a deal with stock = 10
        CreateDealRequest req = new CreateDealRequest(
                UUID.randomUUID(),
                new BigDecimal("149.99"),
                STOCK,
                1,      // min_participants = 1 so first join activates immediately
                1440
        );
        var deal = dealService.createDeal(req, CatalogClientStub.STUB_SELLER_ID);

        // Fire 20 threads simultaneously, each with a unique requestId
        List<Future<SlotResponse>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                UUID requestId = UUID.randomUUID();
                futures.add(pool.submit(() -> dealService.reserveSlot(deal.id(), requestId)));
            }

            List<SlotResponse> responses = new ArrayList<>();
            for (Future<SlotResponse> f : futures) {
                responses.add(f.get());
            }

            long successes  = responses.stream().filter(SlotResponse::success).count();
            long rejections = responses.stream().filter(r -> !r.success()).count();

            assertThat(successes).isEqualTo(STOCK);
            assertThat(rejections).isEqualTo(THREADS - STOCK);

            // The DB counter must also match — never exceeded stock
            var finalDeal = dealService.getDeal(deal.id());
            assertThat(finalDeal.currentParticipants()).isEqualTo(STOCK);
        } finally {
            pool.shutdown();
        }

    }
}
