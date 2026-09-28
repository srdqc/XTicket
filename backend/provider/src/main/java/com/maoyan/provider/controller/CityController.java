package com.maoyan.provider.controller;

import com.maoyan.domain.model.vo.CityVO;
import com.maoyan.service.CityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 城市接口控制器
 * <p>
 * - GET /api/cities → 城市列表
 * - GET /dianying/cities.json → 旧版前端兼容路径
 * </p>
 */
@Slf4j
@RestController
public class CityController {

    @Resource
    private CityService cityService;

    /**
     * 获取全部城市列表
     * 返回格式: { cts: [...] }
     * <p>
     * canonical 与旧版兼容路径返回相同 contract。
     * </p>
     */
    @GetMapping({"/api/cities", "/dianying/cities.json"})
    public Map<String, Object> getCities() {
        List<CityVO> cities = cityService.getAllCities();
        return Map.of("cts", cities);
    }
}
