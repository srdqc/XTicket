package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.dto.OrderSnapshotSourceDTO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 活动场次 Mapper。
 *
 * <p>Phase 3A 已切换到底层 activity_session 表，上层 schedule API 暂时兼容。</p>
 */
public interface ActivitySessionMapper extends BaseMapper<ActivitySessionPO> {

    /**
     * 查询电影在指定日期的场次（按影院分组展示）
     */
    @Select("SELECT * FROM activity_session WHERE activity_id = #{movieId} AND show_date = #{showDate} AND status = 1 AND deleted = 0 ORDER BY show_time ASC")
    List<ActivitySessionPO> selectByMovieAndDate(@Param("movieId") Long movieId, @Param("showDate") String showDate);

    /**
     * 原子条件扣减库存 — 防超卖核心 SQL。
     *
     * <p>InnoDB 在主键行锁内重新评估 available_seats >= seatCount，
     * 返回影响行数：1=成功，0=库存不足或场次无效。</p>
     */
    @Update("UPDATE activity_session SET available_seats = available_seats - #{seatCount}, version = version + 1, update_time = CURRENT_TIMESTAMP WHERE id = #{scheduleId} AND available_seats >= #{seatCount} AND deleted = 0")
    int deductStock(@Param("scheduleId") Long scheduleId, @Param("seatCount") int seatCount);

    /**
     * 回滚库存（订单取消/退款时）
     */
    @Update("UPDATE activity_session SET available_seats = available_seats + #{seatCount}, version = version + 1, update_time = CURRENT_TIMESTAMP WHERE id = #{scheduleId} AND deleted = 0")
    int rollbackStock(@Param("scheduleId") Long scheduleId, @Param("seatCount") int seatCount);

    /**
     * 查询指定电影在某日全部影院的场次（购票选影院页）
     */
    @Select("""
        SELECT ms.*, c.nm as cinema_name FROM activity_session ms
        LEFT JOIN venue c ON ms.venue_id = c.id
        WHERE ms.activity_id = #{movieId} AND ms.show_date = #{showDate} AND ms.status = 1 AND ms.deleted = 0
        ORDER BY c.sort_order ASC, ms.show_time ASC
    """)
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "activityId", column = "activity_id"),
        @Result(property = "venueId", column = "venue_id"),
        @Result(property = "hallName", column = "hall_name"),
        @Result(property = "showDate", column = "show_date"),
        @Result(property = "showTime", column = "show_time"),
        @Result(property = "endTime", column = "end_time"),
        @Result(property = "lang", column = "lang"),
        @Result(property = "totalSeats", column = "total_seats"),
        @Result(property = "availableSeats", column = "available_seats"),
        @Result(property = "price", column = "price"),
        @Result(property = "status", column = "status"),
        @Result(property = "version", column = "version")
    })
    List<ActivitySessionPO> selectByMovieAndDateAllCinemas(@Param("movieId") Long movieId, @Param("showDate") String showDate);

    /**
     * 查询某场次的影院名
     */
    @Select("SELECT c.nm FROM activity_session ms LEFT JOIN venue c ON ms.venue_id = c.id WHERE ms.id = #{scheduleId}")
    String selectCinemaNameByScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * 查询创建订单所需的可信快照来源。
     */
    @Select("""
        SELECT ms.id AS schedule_id,
               ms.activity_id AS movie_id,
               m.nm AS movie_name,
               ms.venue_id AS cinema_id,
               c.nm AS cinema_name,
               ms.hall_name,
               ms.show_date,
               ms.show_time,
               ms.price AS unit_price,
               ms.status,
               ms.version
        FROM activity_session ms
        LEFT JOIN activity m ON ms.activity_id = m.id AND m.deleted = 0
        LEFT JOIN venue c ON ms.venue_id = c.id AND c.deleted = 0
        WHERE ms.id = #{scheduleId}
          AND ms.deleted = 0
        LIMIT 1
    """)
    @Results({
        @Result(property = "scheduleId", column = "schedule_id"),
        @Result(property = "movieId", column = "movie_id"),
        @Result(property = "movieName", column = "movie_name"),
        @Result(property = "cinemaId", column = "cinema_id"),
        @Result(property = "cinemaName", column = "cinema_name"),
        @Result(property = "hallName", column = "hall_name"),
        @Result(property = "showDate", column = "show_date"),
        @Result(property = "showTime", column = "show_time"),
        @Result(property = "unitPrice", column = "unit_price"),
        @Result(property = "status", column = "status"),
        @Result(property = "version", column = "version")
    })
    OrderSnapshotSourceDTO selectOrderSnapshotSource(@Param("scheduleId") Long scheduleId);

    /**
     * 查询某影院有排片的电影ID列表（今天及之后）
     */
    @Select("SELECT DISTINCT activity_id FROM activity_session WHERE venue_id = #{cinemaId} AND show_date >= #{today} AND status = 1 AND deleted = 0")
    List<Long> selectMovieIdsByCinema(@Param("cinemaId") Long cinemaId, @Param("today") String today);

    /**
     * 查询某影院某电影某日的场次
     */
    @Select("SELECT * FROM activity_session WHERE venue_id = #{cinemaId} AND activity_id = #{movieId} AND show_date = #{showDate} AND status = 1 AND deleted = 0 ORDER BY show_time ASC")
    List<ActivitySessionPO> selectByCinemaAndMovieAndDate(@Param("cinemaId") Long cinemaId, @Param("movieId") Long movieId, @Param("showDate") String showDate);

    /**
     * 查询某影院某电影有排片的日期列表
     */
    @Select("SELECT DISTINCT show_date FROM activity_session WHERE venue_id = #{cinemaId} AND activity_id = #{movieId} AND show_date >= #{today} AND status = 1 AND deleted = 0 ORDER BY show_date ASC")
    List<String> selectAvailableDatesByCinemaAndMovie(@Param("cinemaId") Long cinemaId, @Param("movieId") Long movieId, @Param("today") String today);

    // ==================== 启动时日期刷新（演示数据永不过期） ====================

    /**
     * 获取最早的排片日期
     */
    @Select("SELECT MIN(show_date) FROM activity_session WHERE deleted = 0 AND status = 1")
    String selectMinShowDate();

    /**
     * 将所有排片日期前移 daysDiff 天，并重置库存和版本号
     * 这样无论何时启动，排片数据都表现为"今天/明天"
     */
    @Update("UPDATE activity_session SET show_date = DATE_ADD(show_date, INTERVAL #{daysDiff} DAY), available_seats = total_seats, version = 0, update_time = CURRENT_TIMESTAMP WHERE deleted = 0")
    int refreshAllScheduleDates(@Param("daysDiff") long daysDiff);
}
