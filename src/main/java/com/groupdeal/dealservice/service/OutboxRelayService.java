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

    private final DealOutboxRepository dealOutboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static String resolveTopic(String eventType) {
        if (eventType != null && (eventType.startsWith("deal.") || eventType.startsWith("Deal."))) {
            return "deal";
        }
        return "deal-events";
    }

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
                        resolveTopic(entry.getEventType()), entry.getDealId().toString(), entry.getPayload());
                UUID correlationId = UUID.randomUUID();
                record.headers()
                        .add(new RecordHeader("X-Id", entry.getId().toString().getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("X-Type", entry.getEventType().getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("X-Correlation-Id", correlationId.toString().getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("X-Causation-Id", entry.getId().toString().getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("X-Trace-Id", correlationId.toString().getBytes(StandardCharsets.UTF_8)));
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
