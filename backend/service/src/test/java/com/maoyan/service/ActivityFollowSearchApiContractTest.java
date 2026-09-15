package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.dao.mapper.ActivityFollowMapper;
import com.maoyan.dao.mapper.ActivityMapper;
import com.maoyan.domain.model.po.ActivityFollowPO;
import com.maoyan.domain.model.po.ActivityPO;
import com.maoyan.domain.model.vo.api.FollowStatus;
import com.maoyan.domain.model.vo.api.SearchResponse;
import com.maoyan.domain.model.vo.api.ActivitySummary;
import com.maoyan.domain.model.vo.api.VenueSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityFollowSearchApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ActivityFollowMapper activityFollowMapper;
    @Mock
    private ActivityMapper activityMapper;
    @InjectMocks
    private ActivityFollowService activityFollowService;

    @Test
    void duplicateFollowDoesNotInsertOrIncrementAgain() {
        ActivityPO activity = new ActivityPO();
        activity.setId(1L);
        activity.setWish(5);

        when(activityFollowMapper.selectCount(ArgumentMatchers.<Wrapper<ActivityFollowPO>>any())).thenReturn(1L);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        long count = activityFollowService.followActivity(10L, 1L);

        assertThat(count).isEqualTo(5);
        verify(activityFollowMapper, never()).insert(ArgumentMatchers.any(ActivityFollowPO.class));
        verify(activityMapper, never()).incrementFollowCount(1L);
    }

    @Test
    void unfollowDeletesOnceAndDoesNotDecrementAgain() {
        ActivityPO activity = new ActivityPO();
        activity.setId(1L);
        activity.setWish(0);

        when(activityFollowMapper.delete(ArgumentMatchers.<Wrapper<ActivityFollowPO>>any())).thenReturn(1, 0);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        long first = activityFollowService.unfollowActivity(10L, 1L);
        long second = activityFollowService.unfollowActivity(10L, 1L);

        assertThat(first).isZero();
        assertThat(second).isZero();
        verify(activityMapper).decrementFollowCount(1L);
    }

    @Test
    void unfollowWithoutExistingRecordDoesNotDecrementCount() {
        ActivityPO activity = new ActivityPO();
        activity.setId(1L);
        activity.setWish(3);

        when(activityFollowMapper.delete(ArgumentMatchers.<Wrapper<ActivityFollowPO>>any())).thenReturn(0);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        long count = activityFollowService.unfollowActivity(10L, 1L);

        assertThat(count).isEqualTo(3);
        verify(activityMapper, never()).decrementFollowCount(1L);
    }

    @Test
    void followStatusContractDoesNotExposeWishOrMovieId() throws Exception {
        JsonNode json = objectMapper.valueToTree(new FollowStatus(true, 12));

        assertThat(json.get("followed").asBoolean()).isTrue();
        assertThat(json.get("followCount").asLong()).isEqualTo(12);
        assertThat(json.has("wish")).isFalse();
        assertThat(json.has("hasWished")).isFalse();
        assertThat(json.has("movieId")).isFalse();
    }

    @Test
    void searchResponseUsesActivitiesAndVenues() throws Exception {
        ActivitySummary activity = new ActivitySummary();
        activity.setId(1L);
        activity.setName("逐光者");

        VenueSummary venue = new VenueSummary();
        venue.setId(2L);
        venue.setName("学生活动中心");

        SearchResponse response = SearchResponse.fromSearchResult(Map.of(
                "activities", Map.of("list", List.of(activity)),
                "venues", Map.of("list", List.of(venue))
        ), "all");

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(json.has("activities")).isTrue();
        assertThat(json.has("venues")).isTrue();
        assertThat(json.has("movies")).isFalse();
        assertThat(json.has("cinemas")).isFalse();
        assertThat(json.get("activities").get("list").get(0).get("name").asText()).isEqualTo("逐光者");
        assertThat(json.get("venues").get("list").get(0).get("name").asText()).isEqualTo("学生活动中心");
    }

    @Test
    void searchTypeFiltersResponseGroups() {
        SearchResponse activitiesOnly = SearchResponse.fromSearchResult(Map.of(), "activity");
        SearchResponse venuesOnly = SearchResponse.fromSearchResult(Map.of(), "venue");

        assertThat(activitiesOnly.getActivities()).isNotNull();
        assertThat(activitiesOnly.getVenues()).isNull();
        assertThat(venuesOnly.getActivities()).isNull();
        assertThat(venuesOnly.getVenues()).isNotNull();
        assertThat(SearchResponse.supportsType("all")).isTrue();
        assertThat(SearchResponse.supportsType("activity")).isTrue();
        assertThat(SearchResponse.supportsType("venue")).isTrue();
        assertThat(SearchResponse.supportsType("movie")).isFalse();
    }
}
