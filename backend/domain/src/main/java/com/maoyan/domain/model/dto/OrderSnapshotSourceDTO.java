package com.maoyan.domain.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建订单时使用的可信快照来源。
 */
@Data
public class OrderSnapshotSourceDTO {

    private Long scheduleId;
    private Long movieId;
    private String movieName;
    private Long cinemaId;
    private String cinemaName;
    private String hallName;
    private String showDate;
    private String showTime;
    private BigDecimal unitPrice;
    private Integer status;
    private Integer version;
}
