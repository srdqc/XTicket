package com.maoyan.domain.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maoyan.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("electronic_ticket")
public class ElectronicTicketPO extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketNo;
    private Long orderSeatId;
    private String orderNo;
    private Long userId;
    private Long sessionId;
    private Integer status;
    private LocalDateTime issuedAt;
    private LocalDateTime usedAt;
    private Long checkedBy;
    private LocalDateTime invalidatedAt;
}
