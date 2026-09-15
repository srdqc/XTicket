package com.maoyan.domain.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckInResult implements Serializable {

    private String ticketNo;
    private Long sessionId;
    private Integer status;
    private String usedAt;
    private boolean firstCheckIn;
    private boolean alreadyUsed;
    private String activityName;
    private String venueName;
    private String hallName;
    private String showTime;
    private String seatLabel;
}
