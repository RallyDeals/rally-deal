package com.groupdeal.dealservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Deal Service entry point.
 *
 * Owns deal-state: creation rules, the capacity counter (deal_stock / current_participants),
 * the state machine, and the internal timer that resolves deals at end_time.
 *
 * @EnableScheduling powers two future scheduled jobs (not yet implemented — see TODOs):
 *   1. The outbox relay (poll deal_outbox for unpublished rows, publish to Kafka).
 *   2. The end_time sweep (resolve active deals whose timer has expired).
 */
@SpringBootApplication(scanBasePackages = {"com.groupdeal.dealservice", "com.rally.common"})
@EnableScheduling
public class DealServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DealServiceApplication.class, args);
    }
}
