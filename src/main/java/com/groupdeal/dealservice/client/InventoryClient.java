package com.groupdeal.dealservice.client;

import com.groupdeal.dealservice.client.dto.InventoryReservationResult;

import java.util.UUID;

/**
 * Sync dependency on Inventory Service (design doc §6.2).
 * Two implementations wired via `groupdeal.clients.inventory.stub` (application.yml):
 *   - InventoryClientStub  (default — Inventory Service doesn't exist yet)
 *   - InventoryClientHttp  (real WebClient call, ready for when it does)
 */
public interface InventoryClient {

    InventoryReservationResult reserve(UUID productId, Integer quantity);

    void release(UUID productId, Integer quantity);
}
