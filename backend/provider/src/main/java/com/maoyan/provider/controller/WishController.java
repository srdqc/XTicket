package com.maoyan.provider.controller;

import com.maoyan.common.annotation.RateLimit;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.service.ActivityFollowService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 想看接口（需要登录）
 */
@RestController
@RequestMapping("/ajax")
@RequiredArgsConstructor
public class WishController {

    private final ActivityFollowService activityFollowService;

    /**
     * 用户点击"想看" — Redis 实时 + MQ 异步写回
     */
    @PostMapping("/wish/{movieId}")
    @RateLimit(key = "wish", maxRequests = 30, windowSeconds = 60)
    public Result<Map<String, Object>> addWish(@PathVariable Long movieId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }

        long count = activityFollowService.followActivity(userId, movieId);
        boolean hasWished = activityFollowService.hasFollowed(userId, movieId);

        return Result.ok(Map.of(
                "wish", count,
                "hasWished", hasWished
        ));
    }

    /**
     * 查询用户是否已想看某电影
     */
    @GetMapping("/wish/check/{movieId}")
    public Result<Map<String, Object>> checkWish(@PathVariable Long movieId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.ok(Map.of("hasWished", false, "wish", activityFollowService.getFollowCount(movieId)));
        }
        return Result.ok(Map.of(
                "hasWished", activityFollowService.hasFollowed(userId, movieId),
                "wish", activityFollowService.getFollowCount(movieId)
        ));
    }
}
