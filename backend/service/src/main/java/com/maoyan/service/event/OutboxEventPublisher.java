package com.maoyan.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OutboxEventPO;
import com.maoyan.service.observability.BusinessMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@ConditionalOnProperty(name = "rocketmq.name-server")
public class OutboxEventPublisher {

    private static final int BATCH_SIZE = 50;
    private static final int PROCESSING_TIMEOUT_SECONDS = 30;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventMapper outboxEventMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;
    private final ExecutorService publisherExecutor;

    public OutboxEventPublisher(OutboxEventMapper outboxEventMapper,
                                RocketMQTemplate rocketMQTemplate,
                                ObjectMapper objectMapper,
                                BusinessMetrics businessMetrics,
                                @Value("${maoyan.outbox.publisher-concurrency:2}") int publisherConcurrency) {
        if (publisherConcurrency != 1 && publisherConcurrency != 2 && publisherConcurrency != 4) {
            throw new IllegalArgumentException("Outbox publisher concurrency must be 1, 2 or 4");
        }
        this.outboxEventMapper = outboxEventMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.businessMetrics = businessMetrics;
        AtomicInteger threadNumber = new AtomicInteger();
        this.publisherExecutor = Executors.newFixedThreadPool(publisherConcurrency, runnable -> {
            Thread thread = new Thread(runnable,
                    "outbox-publisher-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

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
            List<Callable<Boolean>> tasks = new ArrayList<>(records.size());
            for (OutboxEventPO record : records) {
                tasks.add(() -> claimAndPublish(record, now, staleBefore));
            }
            for (Future<Boolean> result : publisherExecutor.invokeAll(tasks)) {
                if (result.get()) {
                    publishedCount++;
                }
            }
            return records.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox publisher interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Outbox publisher worker failed", e.getCause());
        } finally {
            businessMetrics.recordOutboxBatch(System.nanoTime() - batchStarted, publishedCount);
        }
    }

    private boolean claimAndPublish(OutboxEventPO record, LocalDateTime now, LocalDateTime staleBefore) {
        long claimStarted = System.nanoTime();
        boolean claimed = outboxEventMapper.claim(record.getId(), now, staleBefore) != 0;
        businessMetrics.recordOutboxClaim(System.nanoTime() - claimStarted, claimed);
        return claimed && publishOne(record);
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

    @PreDestroy
    public void shutdownPublisherExecutor() {
        publisherExecutor.shutdown();
    }
}
