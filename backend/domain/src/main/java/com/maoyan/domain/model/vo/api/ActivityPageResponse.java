package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.MovieVO;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityPageResponse implements Serializable {

    private List<ActivitySummary> activities;
    private Long total;
    private Boolean hasMore;

    @SuppressWarnings("unchecked")
    public static ActivityPageResponse fromFilterResult(Map<String, Object> source) {
        ActivityPageResponse response = new ActivityPageResponse();
        List<MovieVO> movies = source == null ? Collections.emptyList() : (List<MovieVO>) source.get("movies");
        response.setActivities(movies == null ? Collections.emptyList() : movies.stream()
                .map(ActivitySummary::from)
                .toList());
        Object total = source == null ? null : source.get("total");
        response.setTotal(total instanceof Number number ? number.longValue() : 0L);
        Object hasMore = source == null ? null : source.get("hasMore");
        response.setHasMore(hasMore instanceof Boolean value ? value : Boolean.FALSE);
        return response;
    }
}
