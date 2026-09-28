package com.maoyan.service;

import com.maoyan.common.constants.CacheConstants;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.ConsumedEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.service.cache.MultiLevelCacheService;
import com.maoyan.service.mq.OrderEventConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock private MultiLevelCacheService cacheService;
    @Mock private ConsumedEventMapper consumedEventMapper;

    @Test
    void duplicateEventIsSkippedBeforeSideEffects() {
        OrderEvent event = OrderEvent.create(OrderEvent.Type.CREATED, "MO-CONSUME-1",
                1001L, 40L, 1, new BigDecimal("65.00"));
        event.setTraceId("phase6a-consumer-trace");
        when(consumedEventMapper.insertIfAbsent(eq(MQConstants.ORDER_CONSUMER_GROUP),
                eq(event.getEventId()), any(LocalDateTime.class))).thenAnswer(invocation -> {
                    assertThat(TraceContext.currentTraceId()).isEqualTo("phase6a-consumer-trace");
                    return 0;
                });
        OrderEventConsumer consumer = new OrderEventConsumer(cacheService, consumedEventMapper);

        consumer.onMessage(event);

        verify(cacheService, never()).evict(CacheConstants.HOT_MOVIES);
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void firstDeliveryRecordsEventAndRunsHandler() {
        OrderEvent event = OrderEvent.create(OrderEvent.Type.CANCELLED, "MO-CONSUME-2",
                1001L, 40L, 1, new BigDecimal("65.00"));
        when(consumedEventMapper.insertIfAbsent(eq(MQConstants.ORDER_CONSUMER_GROUP),
                eq(event.getEventId()), any(LocalDateTime.class))).thenAnswer(invocation -> {
                    assertThat(TraceContext.currentTraceId()).matches("[0-9a-f]{32}");
                    return 1;
                });
        OrderEventConsumer consumer = new OrderEventConsumer(cacheService, consumedEventMapper);

        consumer.onMessage(event);

        verify(cacheService).evict(CacheConstants.HOT_MOVIES);
        assertThat(TraceContext.currentTraceId()).isNull();
    }
}
