package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.OutboxEventPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventPO> {

    @Select("""
            SELECT * FROM outbox_event
            WHERE (status IN ('PENDING', 'FAILED') AND next_retry_time <= #{now})
               OR (status = 'PROCESSING' AND update_time <= #{staleBefore})
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEventPO> selectPublishable(@Param("now") LocalDateTime now,
                                          @Param("staleBefore") LocalDateTime staleBefore,
                                          @Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PROCESSING', update_time = #{now}
            WHERE id = #{id}
              AND ((status IN ('PENDING', 'FAILED') AND next_retry_time <= #{now})
                OR (status = 'PROCESSING' AND update_time <= #{staleBefore}))
            """)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = #{publishedAt}, last_error = NULL,
                update_time = #{publishedAt}
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);

    @Update("""
            UPDATE outbox_event
            SET status = 'FAILED', retry_count = retry_count + 1,
                next_retry_time = #{nextRetryTime}, last_error = #{lastError}, update_time = #{now}
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markFailed(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError, @Param("now") LocalDateTime now);
}
