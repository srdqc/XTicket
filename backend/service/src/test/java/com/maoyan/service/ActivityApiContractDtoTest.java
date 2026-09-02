package com.maoyan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.domain.model.vo.CinemaVO;
import com.maoyan.domain.model.vo.MovieVO;
import com.maoyan.domain.model.vo.ScheduleVO;
import com.maoyan.domain.model.vo.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityApiContractDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void activityResourceUsesIdAndName() throws Exception {
        MovieVO legacy = new MovieVO();
        legacy.setId(1L);
        legacy.setNm("逐光者");
        legacy.setImg("cover");
        legacy.setWish(12);

        JsonNode json = objectMapper.valueToTree(ActivitySummary.from(legacy));

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
        CinemaVO legacy = new CinemaVO();
        legacy.setId(2L);
        legacy.setNm("活动中心");
        legacy.setAddr("校内");

        VenueSummary summary = VenueSummary.from(legacy);

        assertThat(summary.getId()).isEqualTo(2L);
        assertThat(summary.getName()).isEqualTo("活动中心");
        assertThat(summary.getAddress()).isEqualTo("校内");
    }

    @Test
    void sessionResourceUsesActivityVenueSessionIds() throws Exception {
        ScheduleVO legacy = new ScheduleVO();
        legacy.setId(3L);
        legacy.setMovieId(4L);
        legacy.setCinemaId(5L);
        legacy.setHallName("主会场");
        legacy.setLang("中文");
        legacy.setPrice(new BigDecimal("39.90"));

        JsonNode json = objectMapper.valueToTree(SessionSummary.from(legacy));

        assertThat(json.get("sessionId").asLong()).isEqualTo(3L);
        assertThat(json.get("activityId").asLong()).isEqualTo(4L);
        assertThat(json.get("venueId").asLong()).isEqualTo(5L);
        assertThat(json.has("movieId")).isFalse();
        assertThat(json.has("cinemaId")).isFalse();
        assertThat(json.has("scheduleId")).isFalse();
    }

    @Test
    void groupedSessionsUseStructuredVenueObject() throws Exception {
        ScheduleVO legacySession = new ScheduleVO();
        legacySession.setId(6L);
        legacySession.setMovieId(7L);
        legacySession.setCinemaId(8L);

        VenueSessionGroup group = VenueSessionGroup.fromLegacyGroup(Map.of(
                "cinemaId", 8L,
                "cinemaName", "礼堂",
                "cinemaAddr", "东区",
                "schedules", List.of(legacySession)
        ));

        JsonNode json = objectMapper.valueToTree(group);

        assertThat(json.has("venue")).isTrue();
        assertThat(json.get("venue").get("id").asLong()).isEqualTo(8L);
        assertThat(json.get("venue").get("name").asText()).isEqualTo("礼堂");
        assertThat(json.has("cinemaId")).isFalse();
        assertThat(json.has("cinemaName")).isFalse();
        assertThat(json.get("sessions").get(0).has("sessionId")).isTrue();
    }
}
