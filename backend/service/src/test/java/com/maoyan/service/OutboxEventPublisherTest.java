package com.maoyan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OutboxEventPO;
import com.maoyan.service.event.OutboxEventPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock private OutboxEventMapper outboxEventMapper;
    @Mock private RocketMQTemplate rocketMQTemplate;

    @Test
    void claimedRecordIsPublishedAndMarkedPublished() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50))).thenReturn(List.of(record));
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(1);
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper);

        publisher.publishPending();

        verify(rocketMQTemplate).syncSend(eq(MQConstants.ORDER_TOPIC + ":" + MQConstants.TAG_ORDER_CREATED),
                any(OrderEvent.class), eq(1000L));
        verify(outboxEventMapper).markPublished(eq(10L), any(LocalDateTime.class));
        verify(outboxEventMapper, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void unclaimedRecordIsNotPublished() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventPO record = record(objectMapper);
        when(outboxEventMapper.selectPublishable(any(), any(), eq(50))).thenReturn(List.of(record));
        when(outboxEventMapper.claim(eq(10L), any(), any())).thenReturn(0);
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                outboxEventMapper, rocketMQTemplate, objectMapper);

        publisher.publishPending();

        verify(rocketMQTemplate, never()).syncSend(any(), any(OrderEvent.class), eq(1000L));
        verify(outboxEventMapper, never()).markPublished(any(), any());
    }

    private OutboxEventPO record(ObjectMapper objectMapper) throws Exception {
        OrderEvent event = OrderEvent.create(OrderEvent.Type.CREATED, "MO-OUTBOX-2",
                1001L, 40L, 1, new BigDecimal("65.00"));
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
