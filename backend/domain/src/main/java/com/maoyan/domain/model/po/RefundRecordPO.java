package com.maoyan.domain.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maoyan.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Synchronous points-refund fact. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("refund_record")
public class RefundRecordPO extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private String orderNo;
    private String paymentNo;
    private Long userId;
    private BigDecimal amount;
    private Integer points;
    private String status;
    private LocalDateTime refundedAt;
}
