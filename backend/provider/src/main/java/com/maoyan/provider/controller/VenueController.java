package com.maoyan.provider.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.domain.model.dto.api.VenueQueryDTO;
import com.maoyan.domain.model.po.VenuePO;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.domain.model.vo.api.*;
import com.maoyan.service.ActivityService;
import com.maoyan.service.ActivitySessionService;
import com.maoyan.service.VenueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;
    private final ActivitySessionService activitySessionService;
    private final ActivityService activityService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public Result<VenueListResponse> listVenues(VenueQueryDTO query) {
        return Result.ok(VenueListResponse.from(venueService.getVenueList(query.toCinemaQueryDTO())));
    }

    @GetMapping("/filters")
    public Result<VenueFilterResponse> getVenueFilters(@RequestParam Long cityId) {
        return Result.ok(VenueFilterResponse.from(venueService.getFilterOptions(cityId)));
    }

    @GetMapping("/{venueId}")
    public Result<VenueDetail> getVenue(@PathVariable Long venueId) {
        VenuePO venue = activitySessionService.getVenueById(venueId);
        return Result.ok(VenueDetail.from(venue, parseHallTypes(venue)));
    }

    @GetMapping("/{venueId}/activities")
    public Result<List<ActivitySummary>> getVenueActivities(@PathVariable Long venueId) {
        List<Long> activityIds = activitySessionService.getActivityIdsByVenue(venueId);
        if (activityIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        return Result.ok(activityService.getActivitiesByIds(activityIds));
    }

    @GetMapping("/{venueId}/activities/{activityId}/available-dates")
    public Result<List<String>> getVenueActivityAvailableDates(
            @PathVariable Long venueId,
            @PathVariable Long activityId) {
        return Result.ok(activitySessionService.getVenueActivityAvailableDates(venueId, activityId));
    }

    private List<String> parseHallTypes(VenuePO venue) {
        if (venue == null || venue.getHallTypesJson() == null || venue.getHallTypesJson().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(venue.getHallTypesJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse venue hall types, venueId={}", venue.getId(), e);
            return Collections.emptyList();
        }
    }
}
