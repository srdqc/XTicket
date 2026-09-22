package com.maoyan.service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final Counter orderCreated;
    private final Counter paymentSuccess;
    private final Counter refundSuccess;
    private final Counter checkInSuccess;
    private final Counter checkInDuplicate;
    private final Counter outboxPublishSuccess;
    private final Counter outboxPublishFailure;
    private final Map<String, Timer> orderCreateStageTimers;

    private static final String[] ORDER_CREATE_STAGES = {
            "request_validation", "redisson_wait", "load_session", "cleanup_expired_locks",
            "create_missing_lock", "idempotency_check", "verify_locks", "load_snapshot",
            "redis_stock", "db_stock_update", "order_insert", "bind_locks", "refresh_cache",
            "outbox_insert", "response_mapping", "tx_completion", "response_enrichment"
    };

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        orderCreated = registry.counter("xticket.order.created");
        paymentSuccess = registry.counter("xticket.payment.success");
        refundSuccess = registry.counter("xticket.refund.success");
        checkInSuccess = registry.counter("xticket.checkin.success");
        checkInDuplicate = registry.counter("xticket.checkin.duplicate");
        outboxPublishSuccess = registry.counter("xticket.outbox.publish.success");
        outboxPublishFailure = registry.counter("xticket.outbox.publish.failure");
        Map<String, Timer> stageTimers = new LinkedHashMap<>();
        for (String stage : ORDER_CREATE_STAGES) {
            stageTimers.put(stage, Timer.builder("xticket.order.create.stage.duration")
                    .tag("stage", stage)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry));
        }
        orderCreateStageTimers = Map.copyOf(stageTimers);
    }

    public Timer.Sample startTimer() {
        try {
            return Timer.start(registry);
        } catch (RuntimeException e) {
            log.warn("[Metrics] Failed to start timer", e);
            return null;
        }
    }

    public void stopSeatLock(Timer.Sample sample, boolean success) {
        stop(sample, "xticket.seat.lock.duration", success);
    }

    public void stopOrderCreate(Timer.Sample sample, boolean success) {
        stop(sample, "xticket.order.create.duration", success);
    }

    public void stopPayment(Timer.Sample sample, boolean success) {
        stop(sample, "xticket.payment.duration", success);
    }

    public void stopRefund(Timer.Sample sample, boolean success) {
        stop(sample, "xticket.refund.duration", success);
    }

    public void stopOutboxPublish(Timer.Sample sample, boolean success) {
        stop(sample, "xticket.outbox.publish.duration", success);
    }

    public void recordOrderCreateStage(String stage, long durationNanos) {
        Timer timer = orderCreateStageTimers.get(stage);
        if (timer == null) {
            log.warn("[Metrics] Ignored unknown order create stage: {}", stage);
            return;
        }
        try {
            timer.record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("[Metrics] Failed to record order create stage: stage={}", stage, e);
        }
    }

    public Map<String, OrderCreateStageSnapshot> orderCreateStageSnapshots() {
        Map<String, OrderCreateStageSnapshot> snapshots = new LinkedHashMap<>();
        for (String stage : ORDER_CREATE_STAGES) {
            Timer timer = orderCreateStageTimers.get(stage);
            HistogramSnapshot snapshot = timer.takeSnapshot();
            snapshots.put(stage, new OrderCreateStageSnapshot(
                    snapshot.count(),
                    snapshot.mean(TimeUnit.MILLISECONDS),
                    percentileMillis(snapshot, 0.5),
                    percentileMillis(snapshot, 0.95),
                    percentileMillis(snapshot, 0.99),
                    snapshot.max(TimeUnit.MILLISECONDS)));
        }
        return snapshots;
    }

    private Double percentileMillis(HistogramSnapshot snapshot, double percentile) {
        for (ValueAtPercentile value : snapshot.percentileValues()) {
            if (Math.abs(value.percentile() - percentile) < 0.0001) {
                double millis = value.value(TimeUnit.MILLISECONDS);
                return Double.isFinite(millis) ? millis : null;
            }
        }
        return null;
    }

    public record OrderCreateStageSnapshot(
            long count,
            double meanMs,
            Double p50Ms,
            Double p95Ms,
            Double p99Ms,
            double maxMs) {
    }

    public void orderCreated() {
        increment(orderCreated);
    }

    public void paymentSuccess() {
        increment(paymentSuccess);
    }

    public void paymentFailure(String reason) {
        try {
            registry.counter("xticket.payment.failure", "reason", boundedReason(reason)).increment();
        } catch (RuntimeException e) {
            log.warn("[Metrics] Failed to increment payment failure counter", e);
        }
    }

    public void refundSuccess() {
        increment(refundSuccess);
    }

    public void checkInSuccess() {
        increment(checkInSuccess);
    }

    public void checkInDuplicate() {
        increment(checkInDuplicate);
    }

    public void outboxPublishSuccess() {
        increment(outboxPublishSuccess);
    }

    public void outboxPublishFailure() {
        increment(outboxPublishFailure);
    }

    private void stop(Timer.Sample sample, String name, boolean success) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(Timer.builder(name)
                    .tag("outcome", success ? "success" : "failure")
                    .register(registry));
        } catch (RuntimeException e) {
            log.warn("[Metrics] Failed to stop timer: name={}", name, e);
        }
    }

    private void increment(Counter counter) {
        try {
            counter.increment();
        } catch (RuntimeException e) {
            log.warn("[Metrics] Failed to increment counter: name={}", counter.getId().getName(), e);
        }
    }

    private String boundedReason(String reason) {
        return switch (reason) {
            case "not_found", "not_payable", "expired", "seat_lock_expired",
                    "insufficient_points", "state_conflict", "busy" -> reason;
            default -> "other";
        };
    }
}
