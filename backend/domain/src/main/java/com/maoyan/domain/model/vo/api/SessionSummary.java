package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.VenuePO;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSummary implements Serializable {

    private Long sessionId;
    private Long activityId;
    private Long venueId;
    private String venueName;
    private String venueAddress;
    private String hallName;
    private String showDate;
    private String showTime;
    private String endTime;
    private String language;
    private Integer totalSeats;
    private Integer availableSeats;
    private BigDecimal price;

    public static SessionSummary from(ActivitySessionPO po, VenuePO venue, Integer availableSeats) {
        if (po == null) {
            return null;
        }
        SessionSummary summary = new SessionSummary();
        summary.setSessionId(po.getId());
        summary.setActivityId(po.getActivityId());
        summary.setVenueId(po.getVenueId());
        summary.setVenueName(venue == null ? null : venue.getNm());
        summary.setVenueAddress(venue == null ? null : venue.getAddr());
        summary.setHallName(po.getHallName());
        summary.setShowDate(po.getShowDate());
        summary.setShowTime(po.getShowTime());
        summary.setEndTime(po.getEndTime());
        summary.setLanguage(po.getLang());
        summary.setTotalSeats(po.getTotalSeats());
        summary.setAvailableSeats(availableSeats);
        summary.setPrice(po.getPrice());
        return summary;
    }
}
