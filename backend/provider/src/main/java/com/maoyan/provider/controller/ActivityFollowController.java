package com.maoyan.provider.controller;

import com.maoyan.common.annotation.RateLimit;
import com.maoyan.common.constants.CommonConstants;
import com.maoyan.common.utils.JwtUtil;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.domain.model.vo.api.FollowStatus;
import com.maoyan.service.ActivityFollowService;
import com.maoyan.service.ActivityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activities/{activityId}")
@RequiredArgsConstructor
public class ActivityFollowController {

    private final ActivityFollowService activityFollowService;
    private final ActivityService activityService;
    private final JwtUtil jwtUtil;

    @PostMapping("/follow")
    @RateLimit(key = "activity:follow", maxRequests = 30, windowSeconds = 60)
    public Result<FollowStatus> follow(@PathVariable Long activityId, HttpServletRequest request) {
        if (!activityService.activityExists(activityId)) {
            return Result.fail(404, "活动不存在");
        }
        Long userId = resolveUserId(request);
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        long followCount = activityFollowService.followActivity(userId, activityId);
        return Result.ok(new FollowStatus(true, followCount));
    }

    @DeleteMapping("/follow")
    @RateLimit(key = "activity:follow", maxRequests = 30, windowSeconds = 60)
    public Result<FollowStatus> unfollow(@PathVariable Long activityId, HttpServletRequest request) {
        if (!activityService.activityExists(activityId)) {
            return Result.fail(404, "活动不存在");
        }
        Long userId = resolveUserId(request);
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        long followCount = activityFollowService.unfollowActivity(userId, activityId);
        return Result.ok(new FollowStatus(false, followCount));
    }

    @GetMapping("/follow-status")
    public Result<FollowStatus> followStatus(@PathVariable Long activityId, HttpServletRequest request) {
        if (!activityService.activityExists(activityId)) {
            return Result.fail(404, "活动不存在");
        }
        Long userId = resolveUserId(request);
        boolean followed = userId != null && activityFollowService.hasFollowed(userId, activityId);
        long followCount = activityFollowService.getFollowCount(activityId);
        return Result.ok(new FollowStatus(followed, followCount));
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object attribute = request.getAttribute("userId");
        if (attribute instanceof Long userId) {
            return userId;
        }
        String authHeader = request.getHeader(CommonConstants.AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(CommonConstants.TOKEN_PREFIX)) {
            return null;
        }
        String token = authHeader.substring(CommonConstants.TOKEN_PREFIX.length());
        if (!jwtUtil.validate(token)) {
            return null;
        }
        return jwtUtil.getUserId(token);
    }
}
