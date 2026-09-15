package com.maoyan.service;

import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.VenueMapper;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.VenuePO;
import com.maoyan.domain.model.vo.api.SessionSummary;
import com.maoyan.domain.model.vo.api.VenueSessionGroup;
import com.maoyan.domain.model.vo.api.VenueSummary;
import com.maoyan.service.cache.MultiLevelCacheService;
import com.maoyan.service.infrastructure.StockService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 活动场次服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivitySessionService {

    private final ActivitySessionMapper activitySessionMapper;
    private final StockService stockService;
    private final MultiLevelCacheService cacheService;

    @Resource
    private VenueMapper venueMapper;

    private static final String SCHEDULE_LIST_CACHE_PREFIX = "schedule:list:";
    private static final String SCHEDULE_DATES_CACHE_PREFIX = "schedule:dates:";
    private static final String CINEMA_SCHEDULE_LIST_CACHE_PREFIX = "schedule:cinema:list:";
    private static final String CINEMA_SCHEDULE_DATES_CACHE_PREFIX = "schedule:cinema:dates:";

    /**
     * 应用启动时：刷新日期 → 预热库存
     */
    @PostConstruct
    public void init() {
        refreshScheduleDates();
        evictScheduleReadCaches();
        warmUpStock();
    }

    /**
     * 将排片日期平移到当前日期——演示数据永不过期。
     * 算法：取最早排片日期与今天的差值，整体平移所有日期，
     *       同时重置库存和版本号，保证数据始终新鲜。
     */
    private void refreshScheduleDates() {
        String minDateStr = activitySessionMapper.selectMinShowDate();
        if (minDateStr != null) {
            LocalDate minDate = LocalDate.parse(minDateStr);
            LocalDate today = LocalDate.now();
            long daysDiff = ChronoUnit.DAYS.between(minDate, today);
            if (daysDiff > 0) {
                int rows = activitySessionMapper.refreshAllScheduleDates(daysDiff);
                log.info("[Session] 排片日期刷新：前移 {} 天（{} → {}），共更新 {} 条记录", daysDiff, minDate, today, rows);
            }
        }
    }

    /**
     * 每 5 分钟执行一次：检查并修复 Redis 与 DB 的库存不一致
     *
     * <p>触发条件：Redis 回滚失败（记入脏队列）</p>
     */
    @Scheduled(fixedRate = 300000)
    public void reconcileStock() {
        Map<Object, Object> dirty = stockService.getDirtyRollbacks();
        if (dirty == null || dirty.isEmpty()) return;

        log.info("[Session] Starting stock reconciliation, {} dirty records", dirty.size());
        for (Map.Entry<Object, Object> entry : dirty.entrySet()) {
            Long scheduleId = Long.parseLong(entry.getKey().toString());
            int lostRollback = Integer.parseInt(entry.getValue().toString());
            try {
                // 从 DB 查真实库存，强覆盖 Redis
                ActivitySessionPO db = activitySessionMapper.selectById(scheduleId);
                if (db != null) {
                    stockService.initStock(scheduleId, db.getAvailableSeats());
                    stockService.initScheduleDetail(db);
                    log.info("[Session] Reconciled: scheduleId={}, DB stock={}, recovered {} seats",
                            scheduleId, db.getAvailableSeats(), lostRollback);
                }
            } catch (Exception e) {
                log.error("[Session] Failed to reconcile scheduleId={}", scheduleId, e);
            }
        }
        stockService.clearDirtyRollbacks();
        log.info("[Session] Reconciliation completed");
    }

    /**
     * 每天凌晨 0:05 自动刷新排片日期并重新预热库存。
     * 这样即使服务器不重启，排片数据也永远显示"今天/明天"。
     */
    @Scheduled(cron = "0 5 0 * * ?")
    public void dailyRefresh() {
        log.info("[Session] 每日定时刷新排片日期...");
        refreshScheduleDates();
        evictScheduleReadCaches();
        warmUpStock();
        log.info("[Session] 每日定时刷新完成");
    }

    /**
     * 启动时预热库存到 Redis
     */
    public void warmUpStock() {
        String today = LocalDate.now().toString();
        List<ActivitySessionPO> schedules = activitySessionMapper.selectByMovieAndDate(null, today);
        // selectByMovieAndDate 需要 movieId，这里用全量查询
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivitySessionPO> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(ActivitySessionPO::getStatus, 1)
                .eq(ActivitySessionPO::getDeleted, 0);
        schedules = activitySessionMapper.selectList(wrapper);

        for (ActivitySessionPO s : schedules) {
            stockService.initStock(s.getId(), s.getAvailableSeats());
            stockService.initScheduleDetail(s);
        }
        log.info("[Session] Warmed up {} schedules' stock to Redis", schedules.size());
    }

    /**
     * 查询活动某日的场次列表
     */
    public List<SessionSummary> getSessions(Long activityId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }
        List<ActivitySessionPO> pos = getCachedSchedules(activityId, showDate);
        return pos.stream().map(this::toSummary).toList();
    }

    /**
     * 根据ID获取场次
     */
    public ActivitySessionPO getSessionById(Long scheduleId) {
        return activitySessionMapper.selectById(scheduleId);
    }

    /**
     * 查询某活动在某天所有场馆的场次（按场馆分组）。
     */
    public List<VenueSessionGroup> getSessionsByVenue(Long activityId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }

        List<ActivitySessionPO> allSchedules = getCachedSchedules(activityId, showDate);

        Map<Long, List<ActivitySessionPO>> grouped = allSchedules.stream()
                .collect(Collectors.groupingBy(ActivitySessionPO::getVenueId, LinkedHashMap::new, Collectors.toList()));

        List<VenueSessionGroup> result = new ArrayList<>();
        for (Map.Entry<Long, List<ActivitySessionPO>> entry : grouped.entrySet()) {
            VenuePO venue = venueMapper.selectById(entry.getKey());
            VenueSessionGroup group = new VenueSessionGroup();
            group.setVenue(VenueSummary.from(venue, Collections.emptyList()));
            group.setSessions(entry.getValue().stream()
                    .map(session -> toSummary(session, venue))
                    .toList());
            result.add(group);
        }
        return result;
    }

    /**
     * 获取活动有场次的日期列表
     */
    public List<String> getAvailableDates(Long movieId) {
        // 查询未来7天有场次的日期
        String cacheKey = SCHEDULE_DATES_CACHE_PREFIX + movieId;
        return cacheService.get(cacheKey, () -> {
            List<String> dates = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 7; i++) {
                String date = today.plusDays(i).toString();
                List<ActivitySessionPO> schedules = getCachedSchedules(movieId, date);
                if (!schedules.isEmpty()) {
                    dates.add(date);
                }
            }
            return dates;
        });
    }

    private SessionSummary toSummary(ActivitySessionPO po) {
        VenuePO venue = venueMapper.selectById(po.getVenueId());
        return toSummary(po, venue);
    }

    private SessionSummary toSummary(ActivitySessionPO po, VenuePO venue) {
        int redisStock = stockService.getStock(po.getId());
        return SessionSummary.from(po, venue, redisStock >= 0 ? redisStock : po.getAvailableSeats());
    }

    // ==================== 影院详情页专用 ====================

    /**
     * 获取场馆详情
     */
    public VenuePO getVenueById(Long venueId) {
        return venueMapper.selectById(venueId);
    }

    /**
     * 查询某场馆有排片的活动ID列表
     */
    public List<Long> getActivityIdsByVenue(Long venueId) {
        return activitySessionMapper.selectMovieIdsByCinema(venueId, LocalDate.now().toString());
    }

    /**
     * 查询场馆某活动某日的场次
     */
    public List<SessionSummary> getVenueActivitySessions(Long venueId, Long activityId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }
        String finalShowDate = showDate;
        String cacheKey = CINEMA_SCHEDULE_LIST_CACHE_PREFIX + venueId + ":" + activityId + ":" + finalShowDate;
        VenuePO venue = venueMapper.selectById(venueId);
        return cacheService.<List<ActivitySessionPO>>get(cacheKey,
                        () -> activitySessionMapper.selectByCinemaAndMovieAndDate(venueId, activityId, finalShowDate))
                .stream().map(session -> toSummary(session, venue)).toList();
    }

    /**
     * 查询场馆某活动有排片的日期列表
     */
    public List<String> getVenueActivityAvailableDates(Long venueId, Long activityId) {
        String cacheKey = CINEMA_SCHEDULE_DATES_CACHE_PREFIX + venueId + ":" + activityId;
        return cacheService.get(cacheKey,
                () -> activitySessionMapper.selectAvailableDatesByCinemaAndMovie(venueId, activityId, LocalDate.now().toString()));
    }

    private List<ActivitySessionPO> getCachedSchedules(Long activityId, String showDate) {
        String cacheKey = SCHEDULE_LIST_CACHE_PREFIX + activityId + ":" + showDate;
        return cacheService.get(cacheKey, () -> activitySessionMapper.selectByMovieAndDate(activityId, showDate));
    }

    private void evictScheduleReadCaches() {
        cacheService.evictByPrefix(SCHEDULE_LIST_CACHE_PREFIX);
        cacheService.evictByPrefix(SCHEDULE_DATES_CACHE_PREFIX);
        cacheService.evictByPrefix(CINEMA_SCHEDULE_LIST_CACHE_PREFIX);
        cacheService.evictByPrefix(CINEMA_SCHEDULE_DATES_CACHE_PREFIX);
    }
}
