package com.maoyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OutboxEventPO;
import com.maoyan.service.event.OutboxEventPublisher;
import com.maoyan.service.observability.BusinessMetrics;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock private OutboxEventMapper outboxEventMapper;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private BusinessMetrics businessMetrics;

    @Test
    void claimedRecordIsPublishedAndMarkedPublished() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50))).thenReturn(List.of(record));
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TraceContext.currentTraceId()).isEqualTo("phase6a-publisher-trace");
            return null;
        }).when(rocketMQTemplate).syncSend(any(), any(OrderEvent.class), eq(1000L));
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper, businessMetrics);

        publisher.publishPending();

        verify(rocketMQTemplate).syncSend(eq(MQConstants.ORDER_TOPIC + ":" + MQConstants.TAG_ORDER_CREATED),
                any(OrderEvent.class), eq(1000L));
        verify(outboxEventMapper).markPublished(eq(10L), any(LocalDateTime.class));
        verify(outboxEventMapper, never()).markFailed(any(), any(), any(), any());
        verify(businessMetrics).outboxPublishSuccess();
        verify(businessMetrics).recordOutboxPoll(anyLong(), eq(1));
        verify(businessMetrics).recordOutboxClaim(anyLong(), eq(true));
        verify(businessMetrics).recordOutboxSend(anyLong());
        verify(businessMetrics).recordOutboxMarkPublished(anyLong());
        verify(businessMetrics).recordOutboxBatch(anyLong(), eq(1));
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void unclaimedRecordIsNotPublished() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50))).thenReturn(List.of(record));
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(0);
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper, businessMetrics);

        publisher.publishPending();

        verify(rocketMQTemplate, never()).syncSend(any(), any(OrderEvent.class), eq(1000L));
        verify(outboxEventMapper, never()).markPublished(any(), any());
        verify(businessMetrics).recordOutboxClaim(anyLong(), eq(false));
        verify(businessMetrics).recordOutboxBatch(anyLong(), eq(0));
    }

    @Test
    void publishFailureIsMarkedAndCountedWithoutLeakingTrace() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50))).thenReturn(List.of(record));
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rocketMQTemplate).syncSend(any(), any(OrderEvent.class), eq(1000L));
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper, businessMetrics);

        publisher.publishPending();

        verify(outboxEventMapper).markFailed(eq(10L), any(), eq("broker unavailable"), any());
        verify(businessMetrics).outboxPublishFailure();
        verify(businessMetrics).recordOutboxBatch(anyLong(), eq(0));
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void fullBatchImmediatelyPollsAgainAndStopsOnPartialBatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50)))
                .thenReturn(java.util.Collections.nCopies(50, record), List.of());
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(0);
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper, businessMetrics);

        publisher.publishPending();

        verify(outboxEventMapper, times(2)).selectPublishable(any(), any(), eq(50));
        verify(outboxEventMapper, times(50)).claim(eq(10L), any(), any());
        verify(businessMetrics, times(2)).recordOutboxBatch(anyLong(), eq(0));
        verify(rocketMQTemplate, never()).syncSend(any(), any(OrderEvent.class), eq(1000L));
    }

    @Test
    void failedRecordIsRetriedAndPublishedOnNextEligiblePoll() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50))).thenReturn(List.of(record));
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("broker unavailable"))
                .doReturn(null)
                .when(rocketMQTemplate).syncSend(any(), any(OrderEvent.class), eq(1000L));
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper, businessMetrics);

        publisher.publishPending();
        publisher.publishPending();

        verify(outboxEventMapper).markFailed(eq(10L), any(), eq("broker unavailable"), any());
        verify(outboxEventMapper).markPublished(eq(10L), any(LocalDateTime.class));
        verify(rocketMQTemplate, times(2)).syncSend(any(), any(OrderEvent.class), eq(1000L));
    }

    private OutboxEventPO record(ObjectMapper objectMapper) throws Exception {
        OrderEvent event = OrderEvent.create(OrderEvent.Type.CREATED, "MO-OUTBOX-2",
                1001L, 40L, 1, new BigDecimal("65.00"));
        event.setTraceId("phase6a-publisher-trace");
        OutboxEventPO record = new OutboxEventPO();
        record.setId(10L);
        record.setEventId(event.getEventId());
        record.setAggregateId(event.getOrderNo());
        record.setEventType(event.getType().name());
        record.setTopic(MQConstants.ORDER_TOPIC);
        record.setTag(MQConstants.TAG_ORDER_CREATED);
        record.setPayload(objectMapper.writeValueAsString(event));
        record.setRetryCount(0);
        return record;
    }
}
