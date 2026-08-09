package com.groupdeal.dealservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Transactional outbox row — written in the SAME transaction as the `deals` update
 * it describes, so a state change and the fact that an event needs to go out can
 * never diverge (see design doc §3 / §6.3).
 *
 * A separate relay (step 5 — not yet implemented) polls for published_at IS NULL,
 * publishes to Kafka, then marks the row published. This is at-least-once delivery:
 * consumers of deal.* events must treat them as idempotent.
 */
@Entity
@Table(name = "deal_outbox")
@Getter
@Setter
@NoArgsConstructor
public class DealOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    /** e.g. "deal.created" | "deal.cancelled" | "deal.succeeded" | "deal.failed" */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** Fully-formed event body, exactly as it will be published (design doc §6.3 schemas). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public DealOutbox(Long dealId, String eventType, String payload) {
        this.dealId = dealId;
        this.eventType = eventType;
        this.payload = payload;
    }
}
