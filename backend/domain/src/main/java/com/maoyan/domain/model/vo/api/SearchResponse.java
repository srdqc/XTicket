package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    public static SearchResponse fromSearchResult(Map<String, Object> result, String type) {
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

    private static List<ActivitySummary> toActivities(Map<String, Object> result) {
        if (result == null || !(result.get("activities") instanceof Map<?, ?> activities)) {
            return Collections.emptyList();
        }
        Object list = activities.get("list");
        if (!(list instanceof List<?> values)) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(ActivitySummary.class::isInstance)
                .map(ActivitySummary.class::cast)
                .toList();
    }

    private static List<VenueSummary> toVenues(Map<String, Object> result) {
        if (result == null || !(result.get("venues") instanceof Map<?, ?> venues)) {
            return Collections.emptyList();
        }
        Object list = venues.get("list");
        if (!(list instanceof List<?> values)) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(VenueSummary.class::isInstance)
                .map(VenueSummary.class::cast)
                .toList();
    }

    @Data
    @AllArgsConstructor
    public static class ResultGroup<T> implements Serializable {
        private List<T> list;
    }
}
