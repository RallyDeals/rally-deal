package com.groupdeal.dealservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Idempotency ledger for the synchronous reserve-slot / release-slot calls
 * (design doc §5.4 / §5.5). request_id is the caller-generated key; because it's
 * the primary key, a retried insert of the same request_id is rejected by Postgres
 * itself rather than needing app-level "check then insert" logic.
 *
 * NOTE: userId + the partial unique index on (deal_id, user_id) for currently-active
 * reservations (discussed re: "what if participant-id was the user-id") is a candidate
 * follow-up once Participation Service's contract is finalized — not included yet.
 */
@Entity
@Table(name = "deal_slot_requests")
@Getter
@Setter
@NoArgsConstructor
public class DealSlotRequest {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "deal_id", nullable = false, columnDefinition = "uuid")
    private UUID dealId;

    /** "RESERVE" | "RELEASE" | "AUTHORIZE" | "RELEASE_AUTHORIZED" */
    @Column(name = "operation", nullable = false)
    private String operation;

    /** "SUCCESS" | "REJECTED" */
    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public DealSlotRequest(UUID requestId, UUID dealId, String operation, String result) {
        this.requestId = requestId;
        this.dealId = dealId;
        this.operation = operation;
        this.result = result;
    }
}
