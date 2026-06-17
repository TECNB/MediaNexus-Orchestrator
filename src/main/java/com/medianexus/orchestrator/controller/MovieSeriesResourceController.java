package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSeasonsResponse;
import com.medianexus.orchestrator.service.MovieSeriesResourceSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources")
@Tag(name = "电影剧集资源搜索", description = "面向前端选择器的 Radarr/Sonarr 只读搜索接口")
public class MovieSeriesResourceController {

    private final MovieSeriesResourceSearchService resourceSearchService;

    public MovieSeriesResourceController(MovieSeriesResourceSearchService resourceSearchService) {
        this.resourceSearchService = resourceSearchService;
    }

    @GetMapping("/movies/search")
    @Operation(summary = "搜索电影", description = "按关键词代理 Radarr movie lookup，并返回前端选择器字段。")
    public ApiResponse<MovieSearchResponse> searchMovies(
            @Parameter(description = "电影搜索关键词，不能为空")
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(resourceSearchService.searchMovies(term));
    }

    @GetMapping("/series/search")
    @Operation(summary = "搜索剧集", description = "按关键词代理 Sonarr series lookup，并返回前端选择器字段。")
    public ApiResponse<SeriesSearchResponse> searchSeries(
            @Parameter(description = "剧集搜索关键词，不能为空")
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(resourceSearchService.searchSeries(term));
    }

    @GetMapping("/series/seasons")
    @Operation(summary = "获取剧集季数", description = "按 TVDB id 代理 Sonarr lookup，并从 seasons 提取可选季数。")
    public ApiResponse<SeriesSeasonsResponse> getSeriesSeasons(
            @Parameter(description = "TVDB id，必须大于 0")
            @RequestParam(name = "tvdb_id", required = false) Integer tvdbId
    ) {
        return ApiResponse.success(resourceSearchService.getSeriesSeasons(tvdbId));
    }
}
