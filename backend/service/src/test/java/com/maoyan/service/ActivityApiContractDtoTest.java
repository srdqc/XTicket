package com.maoyan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.domain.model.po.ActivityPO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.VenuePO;
import com.maoyan.domain.model.vo.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityApiContractDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void activityResourceUsesIdAndName() throws Exception {
        ActivityPO activity = new ActivityPO();
        activity.setId(1L);
        activity.setNm("逐光者");
        activity.setImg("cover");
        activity.setWish(12);

        JsonNode json = objectMapper.valueToTree(ActivitySummary.from(activity));

        assertThat(json.has("id")).isTrue();
        assertThat(json.has("name")).isTrue();
        assertThat(json.has("coverUrl")).isTrue();
        assertThat(json.has("followCount")).isTrue();
        assertThat(json.has("movieId")).isFalse();
        assertThat(json.has("movieName")).isFalse();
        assertThat(json.get("name").asText()).isEqualTo("逐光者");
    }

    @Test
    void venueResourceUsesIdAndName() {
        VenuePO venue = new VenuePO();
        venue.setId(2L);
        venue.setNm("活动中心");
        venue.setAddr("校内");

        VenueSummary summary = VenueSummary.from(venue, List.of());

        assertThat(summary.getId()).isEqualTo(2L);
        assertThat(summary.getName()).isEqualTo("活动中心");
        assertThat(summary.getAddress()).isEqualTo("校内");
    }

    @Test
    void sessionResourceUsesActivityVenueSessionIds() throws Exception {
        ActivitySessionPO session = new ActivitySessionPO();
        session.setId(3L);
        session.setActivityId(4L);
        session.setVenueId(5L);
        session.setHallName("主会场");
        session.setLang("中文");
        session.setPrice(new BigDecimal("39.90"));

        JsonNode json = objectMapper.valueToTree(SessionSummary.from(session, null, 10));

        assertThat(json.get("sessionId").asLong()).isEqualTo(3L);
        assertThat(json.get("activityId").asLong()).isEqualTo(4L);
        assertThat(json.get("venueId").asLong()).isEqualTo(5L);
        assertThat(json.has("movieId")).isFalse();
        assertThat(json.has("cinemaId")).isFalse();
        assertThat(json.has("scheduleId")).isFalse();
    }

    @Test
    void groupedSessionsUseStructuredVenueObject() throws Exception {
        ActivitySessionPO session = new ActivitySessionPO();
        session.setId(6L);
        session.setActivityId(7L);
        session.setVenueId(8L);
        VenuePO venue = new VenuePO();
        venue.setId(8L);
        venue.setNm("礼堂");
        venue.setAddr("东区");
        VenueSessionGroup group = new VenueSessionGroup();
        group.setVenue(VenueSummary.from(venue, List.of()));
        group.setSessions(List.of(SessionSummary.from(session, venue, 10)));

        JsonNode json = objectMapper.valueToTree(group);

        assertThat(json.has("venue")).isTrue();
        assertThat(json.get("venue").get("id").asLong()).isEqualTo(8L);
        assertThat(json.get("venue").get("name").asText()).isEqualTo("礼堂");
        assertThat(json.has("cinemaId")).isFalse();
        assertThat(json.has("cinemaName")).isFalse();
        assertThat(json.get("sessions").get(0).has("sessionId")).isTrue();
    }
}
