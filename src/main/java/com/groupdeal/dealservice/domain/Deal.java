package com.groupdeal.dealservice.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps directly to the `deals` table (see V1/V3 migrations / design doc §3).
 *
 * All state transitions MUST go through guarded, conditional UPDATEs
 * (e.g. "WHERE status = 'ACTIVE' AND current_participants < deal_stock"),
 * never a plain save() after a blind read-modify-write — that's the whole
 * point of the schema's CHECK constraints and the `version` column.
 */
@Entity
@Table(name = "deals")
@Getter
@Setter
@NoArgsConstructor
public class Deal {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    private UUID productId;

    @Column(name = "seller_id", nullable = false, columnDefinition = "uuid")
    private UUID sellerId;

    /** Snapshot of the product's base_price from Catalog Service at creation time. Never updated afterward. */
    @Column(name = "original_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal originalPrice;

    /** The final absolute price a buyer pays on success — NOT a discount amount/delta. */
    @Column(name = "deal_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal dealPrice;

    /** Deal-level capacity cap. Distinct from Inventory Service's product-level stock. */
    @Column(name = "deal_stock", nullable = false)
    private Integer dealStock;

    /** Live count of joined buyers. One unit per participant (FR-051). */
    @Column(name = "current_participants", nullable = false)
    private Integer currentParticipants = 0;

    /** Count of joins whose payment has been authorized (verified ability to pay).
     *  Deal success/failure is based on this, not currentParticipants (spec §2). */
    @Column(name = "authorized_count", nullable = false)
    private Integer authorizedCount = 0;

    @Column(name = "min_participants", nullable = false)
    private Integer minParticipants;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DealStatus status = DealStatus.PENDING;

    @Column(name = "start_time")
    private OffsetDateTime startTime;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "end_time")
    private OffsetDateTime endTime;

    /** Optimistic-lock guard — belt-and-suspenders alongside the WHERE-guarded updates. */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UuidCreator.getTimeOrderedEpoch(); // UUID v7
        }
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
