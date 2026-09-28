package com.maoyan.service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OutboxEventPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderEventOutboxService {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    public void append(OrderEvent.Type type, OrderPO order) {
        OrderEvent event = OrderEvent.create(type, order.getOrderNo(), order.getUserId(),
                order.getScheduleId(), order.getSeatCount(), order.getTotalPrice());
        String traceId = TraceContext.currentTraceId();
        event.setTraceId(TraceContext.isValid(traceId) ? traceId : null);
        LocalDateTime now = LocalDateTime.now();
        OutboxEventPO record = new OutboxEventPO();
        record.setEventId(event.getEventId());
        record.setAggregateType("ORDER");
        record.setAggregateId(order.getOrderNo());
        record.setEventType(type.name());
        record.setTopic(MQConstants.ORDER_TOPIC);
        record.setTag(tagOf(type));
        record.setPayload(serialize(event));
        record.setStatus("PENDING");
        record.setRetryCount(0);
        record.setNextRetryTime(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        outboxEventMapper.insert(record);
    }

    private String serialize(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("订单事件序列化失败", e);
        }
    }

    private String tagOf(OrderEvent.Type type) {
        return switch (type) {
            case CREATED -> MQConstants.TAG_ORDER_CREATED;
            case PAID -> MQConstants.TAG_ORDER_PAID;
            case CANCELLED -> MQConstants.TAG_ORDER_CANCELLED;
            case REFUNDED -> MQConstants.TAG_ORDER_REFUNDED;
        };
    }
}
