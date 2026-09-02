package com.maoyan.biz.movie;

import com.maoyan.domain.model.vo.MovieVO;
import com.maoyan.service.ActivityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电影详情业务编排
 */
@Slf4j
@Service
public class MovieDetailBiz {

    @Resource
    private ActivityService activityService;

    /**
     * 获取电影详情页数据
     * <p>
     * 返回格式: { detailMovie: MovieVO }
     * </p>
     *
     * @param movieId 电影ID
     * @return 电影详情数据，若不存在返回空 detailMovie
     */
    public Map<String, Object> getMovieDetailData(Long movieId) {
        log.info("获取电影详情: movieId={}", movieId);

        MovieVO movie = activityService.getActivityDetail(movieId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detailMovie", movie);
        return result;
    }
}
