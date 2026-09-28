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
        metrics.recordOutboxPoll(1_000_000L, 50);
        metrics.recordOutboxClaim(2_000_000L, true);
        metrics.recordOutboxClaim(2_000_000L, false);
        metrics.recordOutboxSend(3_000_000L);
        metrics.recordOutboxMarkPublished(4_000_000L);
        metrics.recordOutboxBatch(5_000_000L, 49);

        assertThat(registry.counter("xticket.order.created").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.payment.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.payment.failure", "reason", "state_conflict").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.payment.failure", "reason", "other").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.refund.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.checkin.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.checkin.duplicate").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.outbox.publish.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.outbox.publish.failure").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.outbox.publisher.claim.success").count()).isEqualTo(1);
        assertThat(registry.counter("xticket.outbox.publisher.claim.conflict").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.outbox.publisher.poll.duration").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.outbox.publisher.claim.duration").count()).isEqualTo(2);
        assertThat(registry.timer("xticket.outbox.publisher.send.duration").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.outbox.publisher.mark.published.duration").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.outbox.publisher.batch.duration").count()).isEqualTo(1);
        assertThat(registry.summary("xticket.outbox.publisher.selected.count").totalAmount()).isEqualTo(50);
        assertThat(registry.summary("xticket.outbox.publisher.published.per.batch").totalAmount()).isEqualTo(49);
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
        metrics.recordOrderCreateStage("load_session", 1_000_000L);
        metrics.recordOrderCreateStage("pre_stock", 2_000_000L);
        metrics.recordOrderCreateStage("post_stock_to_tx_end", 3_000_000L);
        metrics.recordPaymentStage("payment_order_load", 1_000_000L);
        metrics.recordPaymentStage("payment_order_seat_total", 4_000_000L);
        metrics.recordPaymentStage("payment_order_seat_insert", 2_000_000L);
        metrics.recordPaymentStage("payment_seat_lock_update", 2_000_000L);
        metrics.recordPaymentStage("payment_ticket_issue", 2_000_000L);
        metrics.recordPaymentStage("payment_tx_completion", 3_000_000L);

        assertThat(registry.timer("xticket.seat.lock.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.seat.lock.duration", "outcome", "failure").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.order.create.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.payment.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.refund.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.outbox.publish.duration", "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.timer("xticket.order.create.stage.duration", "stage", "load_session").count()).isEqualTo(1);
        assertThat(metrics.orderCreateStageSnapshots().get("load_session"))
                .satisfies(snapshot -> {
                    assertThat(snapshot.count()).isEqualTo(1);
                    assertThat(snapshot.meanMs()).isGreaterThan(0);
                    assertThat(snapshot.p95Ms()).isNotNull();
                });
        assertThat(metrics.orderCreateStageSnapshots().get("pre_stock").count()).isEqualTo(1);
        assertThat(metrics.orderCreateStageSnapshots().get("post_stock_to_tx_end").count()).isEqualTo(1);
        assertThat(metrics.paymentStageSnapshots().get("payment_order_load").count()).isEqualTo(1);
        assertThat(metrics.paymentStageSnapshots().get("payment_order_seat_total").count()).isEqualTo(1);
        assertThat(metrics.paymentStageSnapshots().get("payment_order_seat_insert").count()).isEqualTo(1);
        assertThat(metrics.paymentStageSnapshots().get("payment_seat_lock_update").count()).isEqualTo(1);
        assertThat(metrics.paymentStageSnapshots().get("payment_ticket_issue").p95Ms()).isNotNull();
        assertThat(metrics.paymentStageSnapshots().get("payment_tx_completion").count()).isEqualTo(1);
    }
}
