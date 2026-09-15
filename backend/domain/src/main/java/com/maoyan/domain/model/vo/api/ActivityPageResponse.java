package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityPageResponse implements Serializable {

    private List<ActivitySummary> activities;
    private Long total;
    private Boolean hasMore;

    public static ActivityPageResponse of(List<ActivitySummary> activities, long total, boolean hasMore) {
        ActivityPageResponse response = new ActivityPageResponse();
        response.setActivities(activities == null ? Collections.emptyList() : activities);
        response.setTotal(total);
        response.setHasMore(hasMore);
        return response;
    }
}
