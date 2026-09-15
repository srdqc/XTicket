package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.CacheConstants;
import com.maoyan.dao.mapper.ActivityMapper;
import com.maoyan.domain.enums.MovieStatusEnum;
import com.maoyan.domain.model.po.ActivityPO;
import com.maoyan.domain.model.vo.api.ActivityDetail;
import com.maoyan.domain.model.vo.api.ActivityPageResponse;
import com.maoyan.domain.model.vo.api.ActivitySummary;
import com.maoyan.service.cache.MultiLevelCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 活动原子服务（使用多级缓存 L1 Caffeine + L2 Redis）
 */
@Slf4j
@Service
public class ActivityService {

    @Resource
    private ActivityMapper activityMapper;

    @Resource
    private MultiLevelCacheService cacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取热映活动列表（带缓存）
     */
    public List<ActivitySummary> getHotActivities() {
        return cacheService.get(CacheConstants.HOT_MOVIES, () -> {
            log.info("从数据库加载热映活动列表");
            LambdaQueryWrapper<ActivityPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityPO::getMovieStatus, MovieStatusEnum.HOT.getCode())
                    .eq(ActivityPO::getDeleted, 0)
                    .orderByAsc(ActivityPO::getSortOrder)
                    .orderByAsc(ActivityPO::getId);
            return activityMapper.selectList(wrapper).stream()
                    .map(this::toSummary)
                    .toList();
        });
    }

    /**
     * 获取热映活动ID列表（带缓存）
     */
    public List<Long> getHotActivityIds() {
        return cacheService.get(CacheConstants.HOT_MOVIES + ":ids", () -> {
            log.info("从数据库加载热映活动ID列表");
            return activityMapper.selectHotMovieIds();
        });
    }

    /**
     * 获取即将上映活动列表（带缓存）
     */
    public List<ActivitySummary> getComingActivities() {
        return cacheService.get(CacheConstants.COMING_MOVIES, () -> {
            log.info("从数据库加载即将上映活动列表");
            LambdaQueryWrapper<ActivityPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityPO::getMovieStatus, MovieStatusEnum.COMING.getCode())
                    .eq(ActivityPO::getDeleted, 0)
                    .orderByAsc(ActivityPO::getSortOrder)
                    .orderByAsc(ActivityPO::getId);
            return activityMapper.selectList(wrapper).stream()
                    .map(this::toSummary)
                    .toList();
        });
    }

    /**
     * 获取即将上映活动ID列表（带缓存）
     */
    public List<Long> getComingActivityIds() {
        return cacheService.get(CacheConstants.COMING_MOVIES + ":ids", () -> {
            log.info("从数据库加载即将上映活动ID列表");
            return activityMapper.selectComingMovieIds();
        });
    }

    /**
     * 获取最受期待活动列表（按关注人数排序前10）
     */
    public List<ActivitySummary> getMostExpected() {
        return cacheService.get(CacheConstants.MOST_EXPECTED, () -> {
            log.info("从数据库加载最受期待活动列表");
            LambdaQueryWrapper<ActivityPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityPO::getMovieStatus, MovieStatusEnum.COMING.getCode())
                    .eq(ActivityPO::getDeleted, 0)
                    .orderByDesc(ActivityPO::getWish)
                    .last("LIMIT 10");
            return activityMapper.selectList(wrapper).stream()
                    .map(this::toSummary)
                    .toList();
        });
    }

    /**
     * 根据ID列表批量查询活动
     */
    public List<ActivitySummary> getActivitiesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ActivityPO> poList = activityMapper.selectByIds(ids);
        // 按传入的 ID 顺序排序（替代 MySQL FIELD() 函数，兼容 H2）
        Map<Long, ActivityPO> poMap = poList.stream()
                .collect(Collectors.toMap(ActivityPO::getId, p -> p, (a, b) -> a));
        return ids.stream()
                .map(poMap::get)
                .filter(Objects::nonNull)
                .map(this::toSummary)
                .toList();
    }

    /**
     * 获取活动详情
     */
    public ActivityDetail getActivityDetail(Long activityId) {
        if (activityId == null) return null;
        ActivityPO po = activityMapper.selectById(activityId);
        if (po == null || po.getDeleted() == 1) {
            return null;
        }
        return toDetail(po);
    }

    /**
     * 判断活动是否存在且未删除。
     */
    public boolean activityExists(Long activityId) {
        if (activityId == null) {
            return false;
        }
        return activityMapper.selectCount(
                new LambdaQueryWrapper<ActivityPO>()
                        .eq(ActivityPO::getId, activityId)
                        .eq(ActivityPO::getDeleted, 0)
        ) > 0;
    }

    /**
     * 搜索活动
     */
    public List<ActivitySummary> searchActivities(String keyword) {
        return activityMapper.searchByKeyword(keyword).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 活动筛选（按类型/地区/年份/状态，支持排序+分页）
     *
     * @return Activity domain page response
     */
    public ActivityPageResponse filterActivities(Integer movieStatus, String cat, String src, Integer year,
                                             String sortBy, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<ActivityPO> poList = activityMapper.filterMovies(movieStatus, cat, src, year, sortBy, offset, pageSize + 1);
        long total = activityMapper.countFilterMovies(movieStatus, cat, src, year);

        boolean hasMore = poList.size() > pageSize;
        if (hasMore) {
            poList = poList.subList(0, pageSize);
        }

        List<ActivitySummary> summaries = poList.stream().map(this::toSummary).toList();

        return ActivityPageResponse.of(summaries, total, hasMore);
    }

    private ActivitySummary toSummary(ActivityPO po) {
        return ActivitySummary.from(po);
    }

    private ActivityDetail toDetail(ActivityPO po) {
        List<String> photos = Collections.emptyList();
        if (po.getPhotos() != null && !po.getPhotos().isEmpty()) {
            try {
                photos = objectMapper.readValue(po.getPhotos(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析活动剧照JSON失败, movieId={}", po.getId(), e);
            }
        }
        return ActivityDetail.from(po, photos);
    }
}
