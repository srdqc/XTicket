package com.maoyan.service.mq;

import com.maoyan.common.constants.MQConstants;
import com.maoyan.dao.mapper.ActivityFollowMapper;
import com.maoyan.dao.mapper.ActivityMapper;
import com.maoyan.domain.model.event.WishEvent;
import com.maoyan.domain.model.po.ActivityFollowPO;
import com.maoyan.domain.model.po.ActivityPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 活动关注写回消费者 — RocketMQ 版
 *
 * <p>订阅 WISH_TOPIC，异步写回 DB，保证最终一致性</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = MQConstants.WISH_TOPIC,
        consumerGroup = MQConstants.WISH_CONSUMER_GROUP
)
public class ActivityFollowWriteBackConsumer implements RocketMQListener<WishEvent> {

    private final ActivityMapper activityMapper;
    private final ActivityFollowMapper activityFollowMapper;

    @Override
    public void onMessage(WishEvent event) {
        try {
            if (event.getDelta() > 0) {
                try {
                    ActivityFollowPO wish = new ActivityFollowPO();
                    wish.setUserId(event.getUserId());
                    wish.setActivityId(event.getMovieId());
                    wish.setCreateTime(LocalDateTime.now());
                    activityFollowMapper.insert(wish);
                } catch (Exception e) { /* 唯一索引冲突=已存在 */ }
                activityMapper.incrementFollowCount(event.getMovieId());
            } else if (event.getDelta() < 0) {
                int deletedRows = activityFollowMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivityFollowPO>()
                                .eq(ActivityFollowPO::getUserId, event.getUserId())
                                .eq(ActivityFollowPO::getActivityId, event.getMovieId())
                );
                if (deletedRows > 0) {
                    activityMapper.decrementFollowCount(event.getMovieId());
                }
            }
            log.debug("[WishConsumer] Writeback success: userId={}, movieId={}", event.getUserId(), event.getMovieId());
        } catch (Exception e) {
            log.error("[WishConsumer] Writeback failed: userId={}, movieId={}", event.getUserId(), event.getMovieId(), e);
            throw new RuntimeException("想看写回处理失败，触发重试", e);
        }
    }
}
