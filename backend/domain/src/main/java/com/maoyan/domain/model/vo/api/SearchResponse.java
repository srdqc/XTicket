package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.CinemaVO;
import com.maoyan.domain.model.vo.MovieVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchResponse implements Serializable {

    private ResultGroup<ActivitySummary> activities;
    private ResultGroup<VenueSummary> venues;

    public static SearchResponse fromLegacyResult(Map<String, Object> result, String type) {
        SearchResponse response = new SearchResponse();
        String normalizedType = type == null || type.isBlank() ? "all" : type.trim().toLowerCase();
        if ("all".equals(normalizedType) || "activity".equals(normalizedType)) {
            response.setActivities(new ResultGroup<>(toActivities(result)));
        }
        if ("all".equals(normalizedType) || "venue".equals(normalizedType)) {
            response.setVenues(new ResultGroup<>(toVenues(result)));
        }
        return response;
    }

    public static boolean supportsType(String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        String normalizedType = type.trim().toLowerCase();
        return "all".equals(normalizedType) || "activity".equals(normalizedType) || "venue".equals(normalizedType);
    }

    @SuppressWarnings("unchecked")
    private static List<ActivitySummary> toActivities(Map<String, Object> result) {
        if (result == null || !(result.get("movies") instanceof Map<?, ?> movies)) {
            return Collections.emptyList();
        }
        Object list = movies.get("list");
        if (!(list instanceof List<?> values)) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(MovieVO.class::isInstance)
                .map(MovieVO.class::cast)
                .map(ActivitySummary::from)
                .toList();
    }

    private static List<VenueSummary> toVenues(Map<String, Object> result) {
        if (result == null || !(result.get("cinemas") instanceof Map<?, ?> cinemas)) {
            return Collections.emptyList();
        }
        Object list = cinemas.get("list");
        if (!(list instanceof List<?> values)) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(CinemaVO.class::isInstance)
                .map(CinemaVO.class::cast)
                .map(VenueSummary::from)
                .toList();
    }

    @Data
    @AllArgsConstructor
    public static class ResultGroup<T> implements Serializable {
        private List<T> list;
    }
}
