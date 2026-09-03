package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.CacheConstants;
import com.maoyan.dao.mapper.ActivityMapper;
import com.maoyan.domain.enums.MovieStatusEnum;
import com.maoyan.domain.model.po.ActivityPO;
import com.maoyan.domain.model.vo.MovieVO;
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
    public List<MovieVO> getHotActivities() {
        return cacheService.get(CacheConstants.HOT_MOVIES, () -> {
            log.info("从数据库加载热映活动列表");
            LambdaQueryWrapper<ActivityPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityPO::getMovieStatus, MovieStatusEnum.HOT.getCode())
                    .eq(ActivityPO::getDeleted, 0)
                    .orderByAsc(ActivityPO::getSortOrder)
                    .orderByAsc(ActivityPO::getId);
            return activityMapper.selectList(wrapper).stream()
                    .map(this::toVO)
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
    public List<MovieVO> getComingActivities() {
        return cacheService.get(CacheConstants.COMING_MOVIES, () -> {
            log.info("从数据库加载即将上映活动列表");
            LambdaQueryWrapper<ActivityPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityPO::getMovieStatus, MovieStatusEnum.COMING.getCode())
                    .eq(ActivityPO::getDeleted, 0)
                    .orderByAsc(ActivityPO::getSortOrder)
                    .orderByAsc(ActivityPO::getId);
            return activityMapper.selectList(wrapper).stream()
                    .map(this::toVO)
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
    public List<MovieVO> getMostExpected() {
        return cacheService.get(CacheConstants.MOST_EXPECTED, () -> {
            log.info("从数据库加载最受期待活动列表");
            LambdaQueryWrapper<ActivityPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ActivityPO::getMovieStatus, MovieStatusEnum.COMING.getCode())
                    .eq(ActivityPO::getDeleted, 0)
                    .orderByDesc(ActivityPO::getWish)
                    .last("LIMIT 10");
            return activityMapper.selectList(wrapper).stream()
                    .map(this::toVO)
                    .toList();
        });
    }

    /**
     * 根据ID列表批量查询活动
     */
    public List<MovieVO> getActivitiesByIds(List<Long> ids) {
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
                .map(this::toVO)
                .toList();
    }

    /**
     * 获取活动详情
     */
    public MovieVO getActivityDetail(Long movieId) {
        if (movieId == null) return null;
        ActivityPO po = activityMapper.selectById(movieId);
        if (po == null || po.getDeleted() == 1) {
            return null;
        }
        return toDetailVO(po);
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
    public List<MovieVO> searchActivities(String keyword) {
        return activityMapper.searchByKeyword(keyword).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 活动筛选（按类型/地区/年份/状态，支持排序+分页）
     *
     * @return { movies: MovieVO[], total: long, hasMore: boolean }
     */
    public Map<String, Object> filterActivities(Integer movieStatus, String cat, String src, Integer year,
                                             String sortBy, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<ActivityPO> poList = activityMapper.filterMovies(movieStatus, cat, src, year, sortBy, offset, pageSize + 1);
        long total = activityMapper.countFilterMovies(movieStatus, cat, src, year);

        boolean hasMore = poList.size() > pageSize;
        if (hasMore) {
            poList = poList.subList(0, pageSize);
        }

        List<MovieVO> voList = poList.stream().map(this::toListVO).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("movies", voList);
        result.put("total", total);
        result.put("hasMore", hasMore);
        return result;
    }

    // ========== PO → VO 转换 ==========

    /** 列表页VO（包含筛选所需的cat/src/releaseYear） */
    private MovieVO toListVO(ActivityPO po) {
        MovieVO vo = toVO(po);
        vo.setCat(po.getCat());
        vo.setSrc(po.getSrc());
        vo.setReleaseYear(po.getReleaseYear());
        vo.setDur(po.getDur());
        vo.setPubDesc(po.getPubDesc());
        return vo;
    }

    private MovieVO toVO(ActivityPO po) {
        MovieVO vo = new MovieVO();
        vo.setId(po.getId());
        vo.setNm(po.getNm());
        vo.setImg(po.getImg());
        vo.setStar(po.getStar());
        vo.setShowInfo(po.getShowInfo());
        vo.setWish(po.getWish());
        vo.setGlobalReleased(po.getGlobalReleased() != null && po.getGlobalReleased() == 1);
        vo.setComingTitle(po.getComingTitle());

        // 评分处理：已上映显示数字评分，未上映显示"暂无评分"
        if (po.getGlobalReleased() != null && po.getGlobalReleased() == 1 && po.getSc() != null) {
            vo.setSc(po.getSc());
        } else {
            vo.setSc("暂无评分");
        }

        return vo;
    }

    private MovieVO toDetailVO(ActivityPO po) {
        MovieVO vo = toVO(po);
        vo.setEnm(po.getEnm());
        vo.setCat(po.getCat());
        vo.setSrc(po.getSrc());
        vo.setDur(po.getDur());
        vo.setPubDesc(po.getPubDesc());
        vo.setDra(po.getDra());
        vo.setVd(po.getVd());
        vo.setPn(po.getPn());
        vo.setReleaseYear(po.getReleaseYear());

        // 评分对详情页始终返回数字
        if (po.getSc() != null) {
            vo.setSc(po.getSc());
        }

        // 解析剧照JSON数组
        if (po.getPhotos() != null && !po.getPhotos().isEmpty()) {
            try {
                List<String> photoList = objectMapper.readValue(po.getPhotos(), new TypeReference<>() {});
                vo.setPhotos(photoList);
            } catch (JsonProcessingException e) {
                log.warn("解析活动剧照JSON失败, movieId={}", po.getId(), e);
                vo.setPhotos(Collections.emptyList());
            }
        }

        return vo;
    }
}
