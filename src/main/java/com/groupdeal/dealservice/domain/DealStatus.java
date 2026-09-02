package com.groupdeal.dealservice.domain;

import java.util.List;

/**
 * Mirrors the Postgres `deal_status` enum type (V1 migration).
 * State machine: pending -> (active) -> succeeded | failed
 *                pending -> cancelled
 * succeeded / failed / cancelled are terminal.
 *
 * {@code ALL} is a virtual status used solely for filtering — it expands
 * to every concrete status so callers can request deals regardless of state.
 */
public enum DealStatus {
    PENDING,
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ALL;

    /** The five concrete (persisted) statuses. */
    private static final List<DealStatus> CONCRETE = List.of(PENDING, ACTIVE, SUCCEEDED, FAILED, CANCELLED);

    /**
     * Resolves a filter list: if the list contains {@code ALL}, returns every
     * concrete status; otherwise returns the list unchanged.
     */
    public static List<DealStatus> resolve(List<DealStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return statuses;
        }
        if (statuses.contains(ALL)) {
            return CONCRETE;
        }
        return statuses;
    }
}
