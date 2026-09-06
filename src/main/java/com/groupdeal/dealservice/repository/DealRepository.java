package com.groupdeal.dealservice.repository;

import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.domain.DealStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Guarded-update repository for the deals table (design doc §3–§7).
 *
 * Every slot operation uses a conditional UPDATE (WHERE guards) so the DB itself
 * arbitrates concurrency — no optimistic-lock retry loops needed for the hot path.
 */
public interface DealRepository extends JpaRepository<Deal, UUID>, JpaSpecificationExecutor<Deal> {

    boolean existsByProductIdAndStatusIn(UUID productId, Collection<DealStatus> statuses);

    // ── reserve-slot (DS-06, §5.4) ──────────────────────────────────────────────
    // First join flips PENDING→ACTIVE and sets start_time/end_time.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
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


    @Query("""
            SELECT d FROM Deal d
             WHERE d.id IN :dealIds AND d.status IN :statuses""")
    List<Deal> findDealsByIdAndStatusIn(List<UUID> dealIds, List<DealStatus> statuses);

    // ── Analytics queries ───────────────────────────────────────────────────────

    long countBySellerId(UUID sellerId);

    long countBySellerIdAndStatus(UUID sellerId, DealStatus status);

    long countBySellerIdAndCreatedAtBetween(UUID sellerId, OffsetDateTime from, OffsetDateTime to);

    long countBySellerIdAndStatusIn(UUID sellerId, Collection<DealStatus> statuses);

    long countByStatus(DealStatus status);

    long countByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to);

    long countByStatusIn(Collection<DealStatus> statuses);

    // ── Discount sort query (computed: (originalPrice - dealPrice) / originalPrice * 100) ───────────────────
    @Query(value = """
            SELECT d.*
            FROM deals d
            WHERE (:statuses IS NULL OR d.status IN :statuses)
              AND (:sellerId IS NULL OR d.seller_id = :sellerId)
              AND (:productId IS NULL OR d.product_id = :productId)
              AND (:categoryId IS NULL OR d.category_id = :categoryId)
              AND (:minPrice IS NULL OR d.deal_price >= :minPrice)
              AND (:maxPrice IS NULL OR d.deal_price <= :maxPrice)
            ORDER BY ((d.original_price - d.deal_price) / d.original_price * 100) DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Deal> findAllWithDiscountSort(
            @Param("statuses") List<String> statuses,
            @Param("sellerId") UUID sellerId,
            @Param("productId") UUID productId,
            @Param("categoryId") UUID categoryId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query(value = """
            SELECT count(*)
            FROM deals d
            WHERE (:statuses IS NULL OR d.status IN :statuses)
              AND (:sellerId IS NULL OR d.seller_id = :sellerId)
              AND (:productId IS NULL OR d.product_id = :productId)
              AND (:categoryId IS NULL OR d.category_id = :categoryId)
              AND (:minPrice IS NULL OR d.deal_price >= :minPrice)
              AND (:maxPrice IS NULL OR d.deal_price <= :maxPrice)
            """, nativeQuery = true)
    long countWithDiscountSortFilters(
            @Param("statuses") List<String> statuses,
            @Param("sellerId") UUID sellerId,
            @Param("productId") UUID productId,
            @Param("categoryId") UUID categoryId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice);

    // ── Seller stats query ───────────────────────────────────────────────────────
    @Query(value = """
            SELECT
                COUNT(CASE WHEN d.status IN ('ACTIVE', 'PENDING') THEN 1 END) as active_deal_cnt,
                COALESCE(SUM(CASE WHEN d.status = 'SUCCEEDED' THEN d.deal_price * d.authorized_count ELSE 0 END), 0) as total_revenue,
                COALESCE(SUM(CASE WHEN d.status = 'SUCCEEDED' THEN d.authorized_count ELSE 0 END), 0) as participants_joined,
                COUNT(CASE WHEN d.status = 'SUCCEEDED' THEN 1 END) as succeeded_cnt,
                COUNT(CASE WHEN d.status = 'FAILED' THEN 1 END) as failed_cnt
            FROM deals d
            WHERE d.seller_id = :sellerId
            """, nativeQuery = true)
    SellerStatsProjection findSellerStats(@Param("sellerId") UUID sellerId);
}
