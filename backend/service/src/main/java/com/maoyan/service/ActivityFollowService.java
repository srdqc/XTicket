package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maoyan.common.constants.CacheConstants;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.dao.mapper.ActivityFollowMapper;
import com.maoyan.dao.mapper.ActivityMapper;
import com.maoyan.domain.model.event.WishEvent;
import com.maoyan.domain.model.po.ActivityFollowPO;
import com.maoyan.domain.model.po.ActivityPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 活动关注服务 — 实时处理
 *
 * <h3>架构设计：</h3>
 * <pre>
 * ┌──────────┐     ┌──────────────┐     ┌────────────┐     ┌──────────┐
 * │  用户点击  │ ──→ │ Redis INCR   │ ──→ │  RocketMQ  │ ──→ │  DB 写回  │
 * │  "想看"   │     │ (实时计数)     │     │ (异步解耦)  │     │ (最终一致) │
 * └──────────┘     └──────────────┘     └────────────┘     └──────────┘
 * </pre>
 *
 * <h3>技术要点：</h3>
 * <ol>
 *   <li><b>Redis HINCRBY</b>：原子操作，高并发下保证计数准确</li>
 *   <li><b>Redis Set</b>：用户去重（每个用户对同一电影只能想看一次）</li>
 *   <li><b>MQ 异步写回</b>：削峰填谷，不阻塞主流程</li>
 *   <li><b>最终一致性</b>：Redis 实时展示 + DB 最终持久化</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityFollowService {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;
    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    private final ActivityFollowMapper activityFollowMapper;
    private final ActivityMapper activityMapper;

    /**
     * 用户关注活动（核心方法）
     *
     * @return 操作后的总想看数
     */
    public long followActivity(Long userId, Long movieId) {
        // 当 Redis 可用时：Redis Set 去重 + Hash 计数 + MQ 异步写回
        if (stringRedisTemplate != null) {
            String userWishKey = CacheConstants.USER_WISH_PREFIX + userId;
            Boolean added = stringRedisTemplate.opsForSet().add(userWishKey, String.valueOf(movieId)) == 1;
            if (Boolean.FALSE.equals(added)) {
                log.info("[Wish] User {} already wished movie {}", userId, movieId);
                return getFollowCount(movieId);
            }
            Long count = stringRedisTemplate.opsForHash().increment(
                    CacheConstants.MOVIE_WISH_HASH, String.valueOf(movieId), 1);
            if (rocketMQTemplate != null) {
                try {
                    WishEvent event = new WishEvent(userId, movieId, 1, System.currentTimeMillis());
                    String destination = MQConstants.WISH_TOPIC + ":" + MQConstants.TAG_WISH_WRITEBACK;
                    rocketMQTemplate.syncSend(destination, event);
                    log.info("[Wish] Event sent: userId={}, movieId={}, newCount={}", userId, movieId, count);
                } catch (Exception e) {
                    log.error("[Wish] MQ send failed, doing synchronous DB write", e);
                    syncWriteBack(userId, movieId);
                }
            } else {
                syncWriteBack(userId, movieId);
            }
            return count != null ? count : 0;
        }

        // 降级模式：无 Redis，直接 DB 操作
        LambdaQueryWrapper<ActivityFollowPO> check = new LambdaQueryWrapper<>();
        check.eq(ActivityFollowPO::getUserId, userId).eq(ActivityFollowPO::getActivityId, movieId);
        if (activityFollowMapper.selectCount(check) > 0) {
            log.info("[Wish] User {} already wished movie {} (DB check)", userId, movieId);
            return getFollowCount(movieId);
        }
        syncWriteBack(userId, movieId);
        return getFollowCount(movieId);
    }

    /**
     * 用户取消关注活动。
     *
     * @return 操作后的总关注数
     */
    @Transactional
    public long unfollowActivity(Long userId, Long movieId) {
        boolean removed = false;
        if (stringRedisTemplate != null) {
            String userWishKey = CacheConstants.USER_WISH_PREFIX + userId;
            Long removedCount = stringRedisTemplate.opsForSet().remove(userWishKey, String.valueOf(movieId));
            removed = removedCount != null && removedCount > 0;
            if (removed) {
                Long count = stringRedisTemplate.opsForHash().increment(
                        CacheConstants.MOVIE_WISH_HASH, String.valueOf(movieId), -1);
                if (count != null && count < 0) {
                    stringRedisTemplate.opsForHash().put(
                            CacheConstants.MOVIE_WISH_HASH, String.valueOf(movieId), "0");
                }
            }
        }

        int deletedRows = deleteFollowRecord(userId, movieId);
        if (removed || deletedRows > 0) {
            activityMapper.decrementFollowCount(movieId);
        }
        return getFollowCount(movieId);
    }

    /** 同步写回 DB（降级或 MQ 不可用时） */
    private void syncWriteBack(Long userId, Long movieId) {
        try {
            ActivityFollowPO wish = new ActivityFollowPO();
            wish.setUserId(userId);
            wish.setActivityId(movieId);
            wish.setCreateTime(LocalDateTime.now());
            activityFollowMapper.insert(wish);
        } catch (Exception e) {
            log.debug("[Wish] Wish record already exists: userId={}, movieId={}", userId, movieId);
        }
        activityMapper.incrementFollowCount(movieId);
    }

    private int deleteFollowRecord(Long userId, Long movieId) {
        return activityFollowMapper.delete(
                new LambdaQueryWrapper<ActivityFollowPO>()
                        .eq(ActivityFollowPO::getUserId, userId)
                        .eq(ActivityFollowPO::getActivityId, movieId)
        );
    }

    /**
     * 获取活动实时关注数（优先从 Redis 读取）
     */
    public long getFollowCount(Long movieId) {
        if (stringRedisTemplate != null) {
            try {
                Object val = stringRedisTemplate.opsForHash().get(
                        CacheConstants.MOVIE_WISH_HASH, String.valueOf(movieId));
                if (val != null) return Long.parseLong(val.toString());
            } catch (Exception e) {
                log.warn("[Wish] Redis read failed for movieId={}", movieId, e);
            }
        }
        // 降级：从 DB 查询
        ActivityPO movie = activityMapper.selectById(movieId);
        return movie != null ? movie.getWish() : 0;
    }

    /**
     * 检查用户是否已关注活动
     */
    public boolean hasFollowed(Long userId, Long movieId) {
        if (stringRedisTemplate != null) {
            try {
                return Boolean.TRUE.equals(
                        stringRedisTemplate.opsForSet().isMember(
                                CacheConstants.USER_WISH_PREFIX + userId,
                                String.valueOf(movieId)
                        )
                );
            } catch (Exception e) {
                log.warn("[Wish] Redis check failed, fallback to DB", e);
            }
        }
        return activityFollowMapper.selectCount(
                new LambdaQueryWrapper<ActivityFollowPO>()
                        .eq(ActivityFollowPO::getUserId, userId)
                        .eq(ActivityFollowPO::getActivityId, movieId)
        ) > 0;
    }
}
