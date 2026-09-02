package com.maoyan.domain.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户活动关注记录。
 *
 * <p>旧 wish API 暂时保留，底层事实源已迁移为 activity_follow。</p>
 */
@Data
@TableName("activity_follow")
public class ActivityFollowPO implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long activityId;

    private LocalDateTime createTime;
}
