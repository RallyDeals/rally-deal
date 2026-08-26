package com.groupdeal.dealservice.service;

import com.groupdeal.dealservice.domain.DealOutbox;
import com.groupdeal.dealservice.repository.DealOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox relay (design doc §3 / §6.3).
 *
 * Polls deal_outbox for unpublished rows, publishes each to Kafka keyed by deal_id
 * (ordering per deal is critical — §6.3), then marks them published. At-least-once
 * delivery: consumers must treat deal.* events as idempotent.
 *
 * Runs every 2 seconds. Batch size limited to 50 to keep transactions short.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayService {

    private static final String TOPIC = "deal-events";

    private final DealOutboxRepository dealOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedRate = 2000)
    @Transactional
    public void relayUnpublishedEvents() {
        List<DealOutbox> pending = dealOutboxRepository.findTop50ByPublishedAtIsNullOrderByIdAsc();
        if (pending.isEmpty()) {
            return;
        }

        for (DealOutbox entry : pending) {
            try {
                // Key by deal_id so Kafka guarantees ordering per deal
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        "deal-events", entry.getDealId().toString(), entry.getPayload());
                record.headers()
                        .add(new RecordHeader("X-Id", entry.getId().toString().getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("X-Type", entry.getEventType().getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("X-Correlation-Id", (UUID.randomUUID()).toString().getBytes(StandardCharsets.UTF_8)));
                kafkaTemplate.send(record);
                entry.setPublishedAt(OffsetDateTime.now());
                dealOutboxRepository.save(entry);
                log.debug("Published outbox entry {} (type={}, dealId={})",
                        entry.getId(), entry.getEventType(), entry.getDealId());
            } catch (Exception e) {
                log.error("Failed to publish outbox entry {} (type={}, dealId={}) — will retry next poll",
                        entry.getId(), entry.getEventType(), entry.getDealId(), e);
                // Stop processing this batch — don't skip ahead, preserve per-deal ordering
                break;
            }
        }
    }
}
