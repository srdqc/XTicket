package com.maoyan.service.mq;

import com.maoyan.common.constants.CacheConstants;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.ConsumedEventMapper;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.service.cache.MultiLevelCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单事件消费者 — RocketMQ 版
 *
 * <p>订阅 ORDER_TOPIC 的所有 Tag（CREATED/PAID/CANCELLED/REFUNDED）</p>
 * <p>RocketMQ 自动重试（默认16次），失败后进入 %DLQ% 死信队列</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = MQConstants.ORDER_TOPIC,
        consumerGroup = MQConstants.ORDER_CONSUMER_GROUP,
        selectorExpression = "*"
)
public class OrderEventConsumer implements RocketMQListener<OrderEvent>, RocketMQPushConsumerLifecycleListener {

    private final MultiLevelCacheService cacheService;
    private final ConsumedEventMapper consumedEventMapper;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(OrderEvent event) {
        TraceContext.setOrGenerate(event.getTraceId());
        try {
            log.info("[OrderConsumer] Received: eventId={}, type={}, orderNo={}",
                    event.getEventId(), event.getType(), event.getOrderNo());
            if (event.getEventId() != null && !event.getEventId().isBlank()
                    && consumedEventMapper.insertIfAbsent(MQConstants.ORDER_CONSUMER_GROUP,
                    event.getEventId(), LocalDateTime.now()) == 0) {
                log.info("[OrderConsumer] Duplicate skipped: eventId={}, orderNo={}",
                        event.getEventId(), event.getOrderNo());
                return;
            }
            switch (event.getType()) {
                case CREATED -> handleOrderCreated(event);
                case PAID -> handleOrderPaid(event);
                case CANCELLED -> handleOrderCancelled(event);
                case REFUNDED -> handleOrderRefunded(event);
            }
        } catch (Exception e) {
            log.error("[OrderConsumer] Process failed: eventId={}, type={}, orderNo={}",
                    event.getEventId(), event.getType(), event.getOrderNo(), e);
            throw new RuntimeException("订单事件处理失败，触发重试", e);
        } finally {
            TraceContext.clear();
        }
    }

    private void handleOrderCreated(OrderEvent event) {
        cacheService.evict(CacheConstants.HOT_MOVIES);
        log.debug("[OrderConsumer] Cache evicted for order created: {}", event.getOrderNo());
    }

    private void handleOrderPaid(OrderEvent event) {
        // 扩展: 积分奖励、短信通知等
        log.debug("[OrderConsumer] Order paid: {}", event.getOrderNo());
    }

    private void handleOrderCancelled(OrderEvent event) {
        cacheService.evict(CacheConstants.HOT_MOVIES);
        log.debug("[OrderConsumer] Cache evicted for order cancelled: {}", event.getOrderNo());
    }

    private void handleOrderRefunded(OrderEvent event) {
        // noop
    }
}
