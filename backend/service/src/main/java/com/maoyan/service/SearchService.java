package com.maoyan.service;

import com.maoyan.domain.model.vo.api.ActivitySummary;
import com.maoyan.domain.model.vo.api.VenueSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索原子服务
 */
@Slf4j
@Service
public class SearchService {

    @Resource
    private ActivityService activityService;

    @Resource
    private VenueService venueService;

    /**
     * 综合搜索（活动 + 场馆）
     *
     * @param keyword 关键词
     * @param cityId  城市ID
     * @return 搜索结果 { venues: { list: [...] }, activities: { list: [...] } }
     */
    public Map<String, Object> search(String keyword, Long cityId) {
        log.info("搜索: keyword={}, cityId={}", keyword, cityId);

        List<ActivitySummary> activities = activityService.searchActivities(keyword);
        List<VenueSummary> venues = venueService.searchVenues(keyword, cityId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activities", Map.of("list", activities));
        result.put("venues", Map.of("list", venues));
        return result;
    }

    /**
     * 异步记录搜索行为（用于后续搜索推荐/热词统计）
     */
    @Async("bizTaskExecutor")
    public void recordSearchAsync(String keyword, Long cityId) {
        log.debug("异步记录搜索行为: keyword={}, cityId={}", keyword, cityId);
        // TODO: 写入搜索日志表或消息队列，供数据分析使用
    }
}
