package com.maoyan.service;

import com.maoyan.common.constants.CacheConstants;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.service.infrastructure.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockServiceSessionDetailCacheContractTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    private final StockService stockService = new StockService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(stockService, "stringRedisTemplate", redisTemplate);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void initScheduleDetailWritesSessionDetailKeyAndActivityVenueFields() {
        stockService.initScheduleDetail(
                101L, 201L, 301L,
                "主会场", "2026-09-02", "19:30",
                "21:00", "中文", 120, 80,
                new BigDecimal("39.90"), 1, 7
        );

        ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("session:detail:101"), fieldsCaptor.capture());
        verify(redisTemplate).expire("session:detail:101", CacheConstants.STOCK_EXPIRE_HOURS, TimeUnit.HOURS);

        Map<String, String> fields = fieldsCaptor.getValue();
        assertThat(fields).containsEntry("activityId", "201");
        assertThat(fields).containsEntry("venueId", "301");
        assertThat(fields).doesNotContainKeys("movieId", "cinemaId");
    }

    @Test
    void getScheduleFromCacheReadsActivityVenueFields() {
        when(hashOperations.entries("session:detail:101")).thenReturn(Map.ofEntries(
                entry("activityId", "201"),
                entry("venueId", "301"),
                entry("hallName", "主会场"),
                entry("showDate", "2026-09-02"),
                entry("showTime", "19:30"),
                entry("endTime", "21:00"),
                entry("lang", "中文"),
                entry("totalSeats", "120"),
                entry("price", "39.90"),
                entry("status", "1"),
                entry("version", "7")
        ));

        ActivitySessionPO session = stockService.getScheduleFromCache(101L);

        assertThat(session.getId()).isEqualTo(101L);
        assertThat(session.getActivityId()).isEqualTo(201L);
        assertThat(session.getVenueId()).isEqualTo(301L);
        assertThat(session.getHallName()).isEqualTo("主会场");
        assertThat(session.getShowDate()).isEqualTo("2026-09-02");
        assertThat(session.getShowTime()).isEqualTo("19:30");
        assertThat(session.getPrice()).isEqualByComparingTo("39.90");
    }

    @Test
    void evictScheduleCacheKeepsStockKeyAndUsesSessionDetailKey() {
        stockService.evictScheduleCache(101L);

        verify(redisTemplate).delete("schedule:stock:101");
        verify(redisTemplate).delete("session:detail:101");
    }
}
