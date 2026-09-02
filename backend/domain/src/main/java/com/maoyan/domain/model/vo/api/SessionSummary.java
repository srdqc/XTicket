package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.ScheduleVO;
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

    public static SessionSummary from(ScheduleVO vo) {
        if (vo == null) {
            return null;
        }
        SessionSummary summary = new SessionSummary();
        summary.setSessionId(vo.getId());
        summary.setActivityId(vo.getMovieId());
        summary.setVenueId(vo.getCinemaId());
        summary.setVenueName(vo.getCinemaNm());
        summary.setVenueAddress(vo.getCinemaAddr());
        summary.setHallName(vo.getHallName());
        summary.setShowDate(vo.getShowDate());
        summary.setShowTime(vo.getShowTime());
        summary.setEndTime(vo.getEndTime());
        summary.setLanguage(vo.getLang());
        summary.setTotalSeats(vo.getTotalSeats());
        summary.setAvailableSeats(vo.getAvailableSeats());
        summary.setPrice(vo.getPrice());
        return summary;
    }
}
