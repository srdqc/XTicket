package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.ConsumedEventPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface ConsumedEventMapper extends BaseMapper<ConsumedEventPO> {

    @Insert("""
            INSERT IGNORE INTO consumed_event (consumer_group, event_id, consumed_at)
            VALUES (#{consumerGroup}, #{eventId}, #{consumedAt})
            """)
    int insertIfAbsent(@Param("consumerGroup") String consumerGroup,
                       @Param("eventId") String eventId,
                       @Param("consumedAt") LocalDateTime consumedAt);
}
