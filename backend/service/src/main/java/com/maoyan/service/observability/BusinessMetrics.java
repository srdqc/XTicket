package com.maoyan.service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        orderCreated = registry.counter("xticket.order.created");
        paymentSuccess = registry.counter("xticket.payment.success");
        refundSuccess = registry.counter("xticket.refund.success");
        checkInSuccess = registry.counter("xticket.checkin.success");
        checkInDuplicate = registry.counter("xticket.checkin.duplicate");
        outboxPublishSuccess = registry.counter("xticket.outbox.publish.success");
        outboxPublishFailure = registry.counter("xticket.outbox.publish.failure");
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
