package com.maoyan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OutboxEventPO;
import com.maoyan.service.event.OrderEventOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventOutboxServiceTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Test
    void appendsVersionedOrderEventAsPendingOutboxRecord() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OrderEventOutboxService service = new OrderEventOutboxService(outboxEventMapper, objectMapper);
        OrderPO order = new OrderPO();
        order.setOrderNo("MO-OUTBOX-1");
        order.setUserId(1001L);
        order.setScheduleId(40L);
        order.setSeatCount(2);
        order.setTotalPrice(new BigDecimal("130.00"));

        TraceContext.setOrGenerate("phase6a-outbox-trace");
        try {
            service.append(OrderEvent.Type.PAID, order);
        } finally {
            TraceContext.clear();
        }

        ArgumentCaptor<OutboxEventPO> captor = ArgumentCaptor.forClass(OutboxEventPO.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEventPO record = captor.getValue();
        assertThat(record.getEventId()).isNotBlank();
        assertThat(record.getAggregateType()).isEqualTo("ORDER");
        assertThat(record.getAggregateId()).isEqualTo("MO-OUTBOX-1");
        assertThat(record.getEventType()).isEqualTo("PAID");
        assertThat(record.getTopic()).isEqualTo(MQConstants.ORDER_TOPIC);
        assertThat(record.getTag()).isEqualTo(MQConstants.TAG_ORDER_PAID);
        assertThat(record.getStatus()).isEqualTo("PENDING");
        assertThat(record.getRetryCount()).isZero();

        JsonNode payload = objectMapper.readTree(record.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(record.getEventId());
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("type").asText()).isEqualTo("PAID");
        assertThat(payload.get("orderNo").asText()).isEqualTo("MO-OUTBOX-1");
        assertThat(payload.get("occurredAt").asLong()).isPositive();
        assertThat(payload.get("traceId").asText()).isEqualTo("phase6a-outbox-trace");
    }
}
