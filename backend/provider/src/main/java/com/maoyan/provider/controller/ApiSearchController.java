package com.maoyan.provider.controller;

import com.maoyan.domain.model.vo.Result;
import com.maoyan.domain.model.vo.api.SearchResponse;
import com.maoyan.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiSearchController {

    private final SearchService searchService;

    @GetMapping("/search")
    public Result<SearchResponse> search(
            @RequestParam String keyword,
            @RequestParam Long cityId,
            @RequestParam(required = false) String type
    ) {
        if (!SearchResponse.supportsType(type)) {
            return Result.fail(400, "搜索类型不支持");
        }
        searchService.recordSearchAsync(keyword, cityId);
        return Result.ok(SearchResponse.fromSearchResult(searchService.search(keyword, cityId), type));
    }
}
