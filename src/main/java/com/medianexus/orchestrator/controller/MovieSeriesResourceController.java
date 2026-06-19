package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.resources.request.MovieOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.SeriesSeasonsResponse;
import com.medianexus.orchestrator.service.MovieSeriesResourceSearchService;
import com.medianexus.orchestrator.service.ProwlarrReleaseIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources")
@Tag(name = "电影剧集资源搜索", description = "面向前端选择器的 Radarr/Sonarr 只读搜索接口")
public class MovieSeriesResourceController {

    private final MovieSeriesResourceSearchService resourceSearchService;
    private final ProwlarrReleaseIngestService prowlarrReleaseIngestService;

    public MovieSeriesResourceController(
            MovieSeriesResourceSearchService resourceSearchService,
            ProwlarrReleaseIngestService prowlarrReleaseIngestService
    ) {
        this.resourceSearchService = resourceSearchService;
        this.prowlarrReleaseIngestService = prowlarrReleaseIngestService;
    }

    @GetMapping("/movies/search")
    @Operation(summary = "搜索电影", description = "按关键词代理 Radarr movie lookup，并返回前端选择器字段。")
    public ApiResponse<MovieSearchResponse> searchMovies(
            @Parameter(description = "电影搜索关键词，不能为空")
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(resourceSearchService.searchMovies(term));
    }

    @PostMapping("/movies/openlist-ingest")
    @Operation(summary = "从资源页创建电影 OpenList 入库任务", description = "后端自动搜索 Prowlarr、选择匹配分辨率的发布资源，解析为 magnet 后创建电影入库任务。")
    public ApiResponse<MovieMagnetIngestTaskResponse> ingestMovieFromResource(
            @Valid @RequestBody MovieOpenListIngestRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.ingestMovie(request));
    }

    @PostMapping("/movies/releases/openlist-ingest")
    @Operation(summary = "使用指定电影发布资源创建 OpenList 入库任务")
    public ApiResponse<MovieMagnetIngestTaskResponse> ingestSelectedMovieRelease(
            @Valid @RequestBody MovieReleaseOpenListIngestRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.ingestSelectedMovie(request));
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

    @PostMapping("/series/openlist-ingest")
    @Operation(summary = "从资源页创建剧集 OpenList 入库任务", description = "后端自动搜索 Prowlarr、选择匹配分辨率的发布资源，解析为 magnet 后创建剧集季度入库任务。")
    public ApiResponse<SeriesMagnetIngestTaskResponse> ingestSeriesFromResource(
            @Valid @RequestBody SeriesOpenListIngestRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.ingestSeries(request));
    }

    @PostMapping("/series/releases/openlist-ingest")
    @Operation(summary = "使用指定剧集发布资源创建 OpenList 入库任务")
    public ApiResponse<SeriesMagnetIngestTaskResponse> ingestSelectedSeriesRelease(
            @Valid @RequestBody SeriesReleaseOpenListIngestRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.ingestSelectedSeries(request));
    }

    @GetMapping("/releases/search")
    @Operation(summary = "搜索可下载的 Prowlarr 发布资源")
    public ApiResponse<ProwlarrReleaseSearchResponse> searchReleases(
            @RequestParam(name = "term", required = false) String term,
            @RequestParam(name = "media_type", required = false) String mediaType,
            @RequestParam(name = "season_number", required = false) Integer seasonNumber
    ) {
        return ApiResponse.success(
                prowlarrReleaseIngestService.searchReleases(term, mediaType, seasonNumber)
        );
    }
}
