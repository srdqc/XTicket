package com.maoyan.domain.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TicketVO implements Serializable {

    private String ticketNo;
    private String orderNo;
    private Long sessionId;
    private Integer status;
    private String statusDesc;
    private String issuedAt;
    private String usedAt;
    private String activityName;
    private String venueName;
    private String hallName;
    private String showTime;
    private String seatLabel;
    private Integer rowNum;
    private Integer colNum;
}
