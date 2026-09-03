package com.maoyan.provider.controller;

import com.maoyan.common.annotation.RateLimit;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.domain.model.vo.api.ActivityDetail;
import com.maoyan.domain.model.vo.api.ActivityPageResponse;
import com.maoyan.domain.model.vo.api.SessionSummary;
import com.maoyan.domain.model.vo.api.VenueSessionGroup;
import com.maoyan.service.ActivityService;
import com.maoyan.service.ActivitySessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final ActivitySessionService activitySessionService;

    @GetMapping
    public Result<ActivityPageResponse> listActivities(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return Result.ok(ActivityPageResponse.fromFilterResult(
                activityService.filterActivities(status, category, source, year, sort, page, pageSize)
        ));
    }

    @GetMapping("/{activityId}")
    public Result<ActivityDetail> getActivity(@PathVariable Long activityId) {
        return Result.ok(ActivityDetail.from(activityService.getActivityDetail(activityId)));
    }

    @GetMapping("/{activityId}/sessions")
    @RateLimit(key = "activity:sessions", maxRequests = 60, windowSeconds = 60)
    public Result<List<SessionSummary>> getActivitySessions(
            @PathVariable Long activityId,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) String showDate) {
        if (venueId != null) {
            return Result.ok(activitySessionService.getVenueActivitySessions(venueId, activityId, showDate).stream()
                    .map(SessionSummary::from)
                    .toList());
        }
        return Result.ok(activitySessionService.getSessions(activityId, showDate).stream()
                .map(SessionSummary::from)
                .toList());
    }

    @GetMapping("/{activityId}/sessions/grouped-by-venue")
    public Result<List<VenueSessionGroup>> getSessionsGroupedByVenue(
            @PathVariable Long activityId,
            @RequestParam(required = false) String showDate) {
        return Result.ok(activitySessionService.getSessionsByVenue(activityId, showDate).stream()
                .map(VenueSessionGroup::fromLegacyGroup)
                .toList());
    }

    @GetMapping("/{activityId}/available-dates")
    public Result<List<String>> getAvailableDates(@PathVariable Long activityId) {
        return Result.ok(activitySessionService.getAvailableDates(activityId));
    }
}
