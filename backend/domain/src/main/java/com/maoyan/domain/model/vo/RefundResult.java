package com.maoyan.domain.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class RefundResult implements Serializable {
    private String orderNo;
    private Integer status;
    private BigDecimal refundedAmount;
    private Integer refundedPoints;
    private String refundTime;
    private Integer invalidatedTicketCount;
}
