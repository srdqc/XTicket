package com.maoyan.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OutboxEventPO;
import com.maoyan.service.observability.BusinessMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
public class OutboxEventPublisher {

    private static final int BATCH_SIZE = 50;
    private static final int PROCESSING_TIMEOUT_SECONDS = 30;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventMapper outboxEventMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;

    @Scheduled(fixedDelayString = "${maoyan.outbox.publish-interval-ms:1000}")
    public void publishPending() {
        TraceContext.setOrGenerate(null);
        try {
            int selectedCount;
            do {
                selectedCount = publishBatch();
            } while (selectedCount == BATCH_SIZE);
        } finally {
            TraceContext.clear();
        }
    }

    private int publishBatch() {
        long batchStarted = System.nanoTime();
        int publishedCount = 0;
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime staleBefore = now.minusSeconds(PROCESSING_TIMEOUT_SECONDS);
            long pollStarted = System.nanoTime();
            List<OutboxEventPO> records = outboxEventMapper.selectPublishable(now, staleBefore, BATCH_SIZE);
            businessMetrics.recordOutboxPoll(System.nanoTime() - pollStarted, records.size());
            for (OutboxEventPO record : records) {
                long claimStarted = System.nanoTime();
                boolean claimed = outboxEventMapper.claim(record.getId(), now, staleBefore) != 0;
                businessMetrics.recordOutboxClaim(System.nanoTime() - claimStarted, claimed);
                if (!claimed) {
                    continue;
                }
                if (publishOne(record)) {
                    publishedCount++;
                }
            }
            return records.size();
        } finally {
            businessMetrics.recordOutboxBatch(System.nanoTime() - batchStarted, publishedCount);
        }
    }

    private boolean publishOne(OutboxEventPO record) {
        Timer.Sample sample = businessMetrics.startTimer();
        boolean success = false;
        try (TraceContext.Scope ignored = TraceContext.open(null)) {
            try {
                OrderEvent event = objectMapper.readValue(record.getPayload(), OrderEvent.class);
                TraceContext.setOrGenerate(event.getTraceId());
                long sendStarted = System.nanoTime();
                rocketMQTemplate.syncSend(record.getTopic() + ":" + record.getTag(), event, 1000);
                businessMetrics.recordOutboxSend(System.nanoTime() - sendStarted);
                long markStarted = System.nanoTime();
                outboxEventMapper.markPublished(record.getId(), LocalDateTime.now());
                businessMetrics.recordOutboxMarkPublished(System.nanoTime() - markStarted);
                businessMetrics.outboxPublishSuccess();
                success = true;
                log.info("[Outbox] Published: eventId={}, type={}, aggregateId={}",
                        record.getEventId(), record.getEventType(), record.getAggregateId());
            } catch (Exception e) {
                LocalDateTime now = LocalDateTime.now();
                int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount();
                long delaySeconds = Math.min(300L, 1L << Math.min(retryCount, 8));
                outboxEventMapper.markFailed(record.getId(), now.plusSeconds(delaySeconds),
                        truncate(e.getMessage()), now);
                businessMetrics.outboxPublishFailure();
                log.warn("[Outbox] Publish failed: eventId={}, type={}, aggregateId={}, retryCount={}",
                        record.getEventId(), record.getEventType(), record.getAggregateId(), retryCount + 1, e);
            } finally {
                businessMetrics.stopOutboxPublish(sample, success);
            }
        }
        return success;
    }

    private String truncate(String message) {
        String value = message == null ? "unknown error" : message;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
