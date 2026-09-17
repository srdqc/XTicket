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
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime staleBefore = now.minusSeconds(PROCESSING_TIMEOUT_SECONDS);
            List<OutboxEventPO> records = outboxEventMapper.selectPublishable(now, staleBefore, BATCH_SIZE);
            for (OutboxEventPO record : records) {
                if (outboxEventMapper.claim(record.getId(), now, staleBefore) == 0) {
                    continue;
                }
                publishOne(record);
            }
        } finally {
            TraceContext.clear();
        }
    }

    private void publishOne(OutboxEventPO record) {
        Timer.Sample sample = businessMetrics.startTimer();
        boolean success = false;
        try (TraceContext.Scope ignored = TraceContext.open(null)) {
            try {
                OrderEvent event = objectMapper.readValue(record.getPayload(), OrderEvent.class);
                TraceContext.setOrGenerate(event.getTraceId());
                rocketMQTemplate.syncSend(record.getTopic() + ":" + record.getTag(), event, 1000);
                outboxEventMapper.markPublished(record.getId(), LocalDateTime.now());
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
    }

    private String truncate(String message) {
        String value = message == null ? "unknown error" : message;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
