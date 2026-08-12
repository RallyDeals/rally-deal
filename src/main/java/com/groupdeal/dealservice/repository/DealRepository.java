package com.groupdeal.dealservice.repository;

import com.groupdeal.dealservice.domain.Deal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Guarded-update repository for the deals table (design doc §3–§7).
 *
 * Every slot operation uses a conditional UPDATE (WHERE guards) so the DB itself
 * arbitrates concurrency — no optimistic-lock retry loops needed for the hot path.
 */
public interface DealRepository extends JpaRepository<Deal, UUID>, JpaSpecificationExecutor<Deal> {

    // ── reserve-slot (DS-06, §5.4) ──────────────────────────────────────────────
    // First join flips PENDING→ACTIVE and sets start_time/end_time.
    @Modifying
    @Query(value = """
            UPDATE deals
            SET current_participants = current_participants + 1,
                status = 'ACTIVE',
                start_time = :startTime,
                end_time = :endTime,
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'PENDING'
              AND current_participants < deal_stock
            """, nativeQuery = true)
    int reserveSlotFirstJoin(@Param("dealId") UUID dealId,
                             @Param("startTime") OffsetDateTime startTime,
                             @Param("endTime") OffsetDateTime endTime);

    // Subsequent joins: deal is already ACTIVE.
    @Modifying
    @Query(value = """
            UPDATE deals
            SET current_participants = current_participants + 1,
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND current_participants < deal_stock
            """, nativeQuery = true)
    int reserveSlotActive(@Param("dealId") UUID dealId);

    // ── release-slot (DS-07, §5.5) ──────────────────────────────────────────────
    // Payment declined before authorization — decrements current_participants only.
    @Modifying
    @Query(value = """
            UPDATE deals
            SET current_participants = current_participants - 1,
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND current_participants > 0
            """, nativeQuery = true)
    int releaseSlot(@Param("dealId") UUID dealId);

    // ── authorize-slot (DS-11, §5.6) ────────────────────────────────────────────
    // Increments authorized_count. Does NOT flip to succeeded (done in service layer
    // by checking if authorized_count == deal_stock after this update).
    @Modifying
    @Query(value = """
            UPDATE deals
            SET authorized_count = authorized_count + 1,
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND authorized_count < deal_stock
            """, nativeQuery = true)
    int authorizeSlot(@Param("dealId") UUID dealId);

    // Flip active→succeeded when authorized_count reaches deal_stock (DS-08).
    @Modifying
    @Query(value = """
            UPDATE deals
            SET status = 'SUCCEEDED',
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND authorized_count = deal_stock
            """, nativeQuery = true)
    int succeedIfFullyAuthorized(@Param("dealId") UUID dealId);

    // ── release-authorized-slot (DS-12, §5.7) ──────────────────────────────────
    // Decrements BOTH counters (participant left after payment was authorized).
    @Modifying
    @Query(value = """
            UPDATE deals
            SET current_participants = current_participants - 1,
                authorized_count = authorized_count - 1,
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND current_participants > 0
              AND authorized_count > 0
            """, nativeQuery = true)
    int releaseAuthorizedSlot(@Param("dealId") UUID dealId);

    // ── timer sweep (DS-09, §7) ─────────────────────────────────────────────────
    // Find all active deals whose end_time has passed, for the scheduled resolver.
    @Query("SELECT d FROM Deal d WHERE d.status = 'ACTIVE' AND d.endTime <= :now")
    List<Deal> findExpiredActiveDeals(@Param("now") OffsetDateTime now);

    // Resolve expired: succeed
    @Modifying
    @Query(value = """
            UPDATE deals
            SET status = 'SUCCEEDED',
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND end_time <= :now
              AND authorized_count >= min_participants
            """, nativeQuery = true)
    int resolveExpiredAsSucceeded(@Param("dealId") UUID dealId, @Param("now") OffsetDateTime now);

    // Resolve expired: fail
    @Modifying
    @Query(value = """
            UPDATE deals
            SET status = 'FAILED',
                updated_at = now(),
                version = version + 1
            WHERE id = :dealId
              AND status = 'ACTIVE'
              AND end_time <= :now
              AND authorized_count < min_participants
            """, nativeQuery = true)
    int resolveExpiredAsFailed(@Param("dealId") UUID dealId, @Param("now") OffsetDateTime now);
}
