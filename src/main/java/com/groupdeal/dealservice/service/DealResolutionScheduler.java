package com.groupdeal.dealservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Internal timer — resolves active deals whose end_time has passed (design doc §7, DS-09).
 *
 * Runs every 5 seconds. The underlying guarded UPDATE (WHERE status = 'ACTIVE')
 * means this safely races with a concurrent authorize-slot that might fill
 * the last slot at the same instant — whichever commits first wins; the other
 * affects 0 rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DealResolutionScheduler {

    private final DealService dealService;

    @Scheduled(fixedRate = 5000)
    public void resolveExpiredDeals() {
        try {
            dealService.resolveExpiredDeals();
        } catch (Exception e) {
            log.error("Error during deal resolution sweep", e);
        }
    }
}
