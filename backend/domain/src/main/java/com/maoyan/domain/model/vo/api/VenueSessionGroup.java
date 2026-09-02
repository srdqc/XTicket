package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.ScheduleVO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VenueSessionGroup implements Serializable {

    private VenueSummary venue;
    private List<SessionSummary> sessions;

    @SuppressWarnings("unchecked")
    public static VenueSessionGroup fromLegacyGroup(Map<String, Object> source) {
        VenueSessionGroup group = new VenueSessionGroup();
        VenueSummary venue = new VenueSummary();
        venue.setId((Long) source.get("cinemaId"));
        venue.setName((String) source.get("cinemaName"));
        venue.setAddress((String) source.get("cinemaAddr"));
        group.setVenue(venue);

        List<ScheduleVO> legacySessions = (List<ScheduleVO>) source.get("schedules");
        group.setSessions(legacySessions == null ? List.of() : legacySessions.stream()
                .map(SessionSummary::from)
                .toList());
        return group;
    }
}
