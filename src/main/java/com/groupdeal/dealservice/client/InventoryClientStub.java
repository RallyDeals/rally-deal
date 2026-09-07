package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.InventoryReservationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stand-in for Inventory Service. Always succeeds, so the create-deal flow (step 3)
 * and the deal.cancelled / deal.succeeded / deal.failed release paths (step 5) can be
 * built and tested before Inventory Service exists.
 *
 * Active by default (groupdeal.clients.inventory.stub=true in application.yml).
 */
@Component
@ConditionalOnProperty(name = "groupdeal.clients.inventory.stub", havingValue = "true", matchIfMissing = true)
public class InventoryClientStub implements InventoryClient {

    @Override
    public InventoryReservationResult reserve(UUID productId, Integer quantity) {
        return new InventoryReservationResult(true, null);
    }

    @Override
    public void release(UUID productId, Integer quantity) {
    }
}
