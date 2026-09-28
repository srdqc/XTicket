package com.maoyan.domain.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单展示对象
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderVO implements Serializable {

    private Long id;
    private String orderNo;
    private String lockToken;
    /** 活动名称（canonical public field） */
    private String activityName;
    /** 场馆名称（canonical public field） */
    private String venueName;
    /** 活动封面（canonical public field） */
    private String activityCoverUrl;
    /** @deprecated 兼容旧版前端，请使用 activityName */
    private String movieName;
    /** @deprecated 兼容旧版前端，请使用 venueName */
    private String cinemaName;
    private String hallName;
    private String showTime;
    private Integer seatCount;
    private String seatsInfo;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    /** 0=待支付 1=已支付 2=已取消 3=已退款 */
    private Integer status;
    private String statusDesc;
    private String createTime;
    private String payTime;
    private String expireTime;
    private Long scheduleId;
    /** @deprecated 兼容旧版前端，请使用 activityCoverUrl */
    private String movieImg;
    /** 支付后剩余积分 */
    private Integer remainingPoints;
}
