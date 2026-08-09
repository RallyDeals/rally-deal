package com.groupdeal.dealservice.domain;

/**
 * Mirrors the Postgres `deal_status` enum type (V1 migration).
 * State machine: pending -> (active) -> succeeded | failed
 *                pending -> cancelled
 * succeeded / failed / cancelled are terminal.
 */
public enum DealStatus {
    PENDING,
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
