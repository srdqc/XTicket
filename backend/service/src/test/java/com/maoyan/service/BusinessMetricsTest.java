package com.maoyan.service;

import com.maoyan.service.observability.BusinessMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsTest {

    @Test
    void countersAndBoundedPaymentFailureReasonAreRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.orderCreated();
        metrics.paymentSuccess();
        metrics.paymentFailure("state_conflict");
        metrics.paymentFailure("unbounded-value");
        metrics.refundSuccess();
        metrics.checkInSuccess();
        metrics.checkInDuplicate();
        metrics.outboxPublishSuccess();
        metrics.outboxPublishFailure();

        assertThat(registry.counter("xticket.order.created").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.payment.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.payment.failure", "reason", "state_conflict").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.payment.failure", "reason", "other").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.refund.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.checkin.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.checkin.duplicate").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.outbox.publish.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.outbox.publish.failure").count()).isEqualTo(1);
    }

    @Test
    void successAndFailureTimersBothRecordWithoutWallClockAssertions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        Timer.Sample success = metrics.startTimer();
        metrics.stopSeatLock(success, true);
        Timer.Sample failure = metrics.startTimer();
        metrics.stopSeatLock(failure, false);
        metrics.stopOrderCreate(metrics.startTimer(), true);
        metrics.stopPayment(metrics.startTimer(), true);
        metrics.stopRefund(metrics.startTimer(), true);
        metrics.stopOutboxPublish(metrics.startTimer(), true);

        assertThat(registry.timer("xticket.seat.lock.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.seat.lock.duration", "outcome", "failure").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.order.create.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.payment.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.refund.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.outbox.publish.duration", "outcome", "success").count()).isEqualTo(1);
    }
}
