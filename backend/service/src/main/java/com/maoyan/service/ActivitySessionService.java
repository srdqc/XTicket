package com.maoyan.service;

import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.VenueMapper;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.VenuePO;
import com.maoyan.domain.model.vo.ScheduleVO;
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
    public List<ScheduleVO> getSessions(Long movieId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }
        List<ActivitySessionPO> pos = getCachedSchedules(movieId, showDate);
        return pos.stream().map(this::toVO).toList();
    }

    /**
     * 根据ID获取场次
     */
    public ActivitySessionPO getSessionById(Long scheduleId) {
        return activitySessionMapper.selectById(scheduleId);
    }

    /**
     * 查询某活动在某天所有场馆的场次（按场馆分组）
     *
     * @return { cinemaId: { cinemaName, cinemaAddr, schedules: [ScheduleVO...] } }
     */
    public List<Map<String, Object>> getSessionsByVenue(Long movieId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }

        List<ActivitySessionPO> allSchedules = getCachedSchedules(movieId, showDate);

        // 按影院分组
        Map<Long, List<ActivitySessionPO>> grouped = allSchedules.stream()
                .collect(Collectors.groupingBy(ActivitySessionPO::getVenueId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<ActivitySessionPO>> entry : grouped.entrySet()) {
            Long cinemaId = entry.getKey();
            VenuePO cinema = venueMapper.selectById(cinemaId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cinemaId", cinemaId);
            item.put("cinemaName", cinema != null ? cinema.getNm() : "未知影院");
            item.put("cinemaAddr", cinema != null ? cinema.getAddr() : "");
            item.put("schedules", entry.getValue().stream().map(this::toVO).toList());
            result.add(item);
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

    private ScheduleVO toVO(ActivitySessionPO po) {
        ScheduleVO vo = new ScheduleVO();
        vo.setId(po.getId());
        vo.setMovieId(po.getActivityId());
        vo.setCinemaId(po.getVenueId());
        vo.setHallName(po.getHallName());
        vo.setShowDate(po.getShowDate());
        vo.setShowTime(po.getShowTime());
        vo.setEndTime(po.getEndTime());
        vo.setLang(po.getLang());
        vo.setTotalSeats(po.getTotalSeats());
        // 优先从 Redis 获取实时库存
        int redisStock = stockService.getStock(po.getId());
        vo.setAvailableSeats(redisStock >= 0 ? redisStock : po.getAvailableSeats());
        vo.setPrice(po.getPrice());
        return vo;
    }

    // ==================== 影院详情页专用 ====================

    /**
     * 获取场馆详情
     */
    public VenuePO getVenueById(Long cinemaId) {
        return venueMapper.selectById(cinemaId);
    }

    /**
     * 查询某场馆有排片的活动ID列表
     */
    public List<Long> getActivityIdsByVenue(Long cinemaId) {
        return activitySessionMapper.selectMovieIdsByCinema(cinemaId, LocalDate.now().toString());
    }

    /**
     * 查询场馆某活动某日的场次
     */
    public List<ScheduleVO> getVenueActivitySessions(Long cinemaId, Long movieId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }
        String finalShowDate = showDate;
        String cacheKey = CINEMA_SCHEDULE_LIST_CACHE_PREFIX + cinemaId + ":" + movieId + ":" + finalShowDate;
        return cacheService.<List<ActivitySessionPO>>get(cacheKey,
                        () -> activitySessionMapper.selectByCinemaAndMovieAndDate(cinemaId, movieId, finalShowDate))
                .stream().map(this::toVO).toList();
    }

    /**
     * 查询场馆某活动有排片的日期列表
     */
    public List<String> getVenueActivityAvailableDates(Long cinemaId, Long movieId) {
        String cacheKey = CINEMA_SCHEDULE_DATES_CACHE_PREFIX + cinemaId + ":" + movieId;
        return cacheService.get(cacheKey,
                () -> activitySessionMapper.selectAvailableDatesByCinemaAndMovie(cinemaId, movieId, LocalDate.now().toString()));
    }

    private List<ActivitySessionPO> getCachedSchedules(Long movieId, String showDate) {
        String cacheKey = SCHEDULE_LIST_CACHE_PREFIX + movieId + ":" + showDate;
        return cacheService.get(cacheKey, () -> activitySessionMapper.selectByMovieAndDate(movieId, showDate));
    }

    private void evictScheduleReadCaches() {
        cacheService.evictByPrefix(SCHEDULE_LIST_CACHE_PREFIX);
        cacheService.evictByPrefix(SCHEDULE_DATES_CACHE_PREFIX);
        cacheService.evictByPrefix(CINEMA_SCHEDULE_LIST_CACHE_PREFIX);
        cacheService.evictByPrefix(CINEMA_SCHEDULE_DATES_CACHE_PREFIX);
    }
}
