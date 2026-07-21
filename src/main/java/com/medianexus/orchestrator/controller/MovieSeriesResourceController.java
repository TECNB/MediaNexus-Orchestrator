package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.resources.request.MovieOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.MovieSearchResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseRecommendationResponse;
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
@Tag(name = "电影剧集资源搜索", description = "面向前端选择器的媒体目录与发布资源接口")
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
    @Operation(summary = "搜索电影", description = "按关键词搜索 TMDB 电影目录，并返回前端选择器字段。")
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

    @PostMapping("/movies/releases/recommendation")
    @Operation(summary = "推荐电影发布资源", description = "使用已选电影的展示标题和原始标题加年份搜索全部启用索引器，并返回可确认的推荐发布。")
    public ApiResponse<ProwlarrReleaseRecommendationResponse> recommendMovieRelease(
            @Valid @RequestBody MovieReleaseRecommendationRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.recommendMovieRelease(request));
    }

    @PostMapping("/movies/releases/recommendation/refresh")
    @Operation(summary = "刷新电影发布资源推荐", description = "绕过搜索缓存重新执行完整电影发布搜索计划，全部成功后替换缓存并返回新推荐。")
    public ApiResponse<ProwlarrReleaseRecommendationResponse> refreshMovieReleaseRecommendation(
            @Valid @RequestBody MovieReleaseRecommendationRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.refreshMovieReleaseRecommendation(request));
    }

    @PostMapping("/movies/releases/search")
    @Operation(summary = "搜索电影发布资源", description = "使用与快速添加一致的展示标题和原始标题搜索计划，合并并去重发布列表。")
    public ApiResponse<ProwlarrReleaseSearchResponse> searchMovieReleases(
            @Valid @RequestBody MovieReleaseSearchRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.searchMovieReleases(request));
    }

    @PostMapping("/movies/releases/search/refresh")
    @Operation(summary = "刷新电影发布资源列表", description = "绕过搜索缓存重新执行完整电影发布搜索计划，全部成功后替换缓存。")
    public ApiResponse<ProwlarrReleaseSearchResponse> refreshMovieReleases(
            @Valid @RequestBody MovieReleaseSearchRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.refreshMovieReleases(request));
    }

    @GetMapping("/series/search")
    @Operation(summary = "搜索剧集", description = "按关键词搜索剧集目录，并返回前端选择器字段。")
    public ApiResponse<SeriesSearchResponse> searchSeries(
            @Parameter(description = "剧集搜索关键词，不能为空")
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(resourceSearchService.searchSeries(term));
    }

    @GetMapping("/series/seasons")
    @Operation(summary = "获取剧集季数", description = "按 TMDB 或 TVDB 剧集目录身份加载可选季数，优先使用 TMDB。")
    public ApiResponse<SeriesSeasonsResponse> getSeriesSeasons(
            @Parameter(description = "TMDB id，必须大于 0")
            @RequestParam(name = "tmdb_id", required = false) Integer tmdbId,
            @Parameter(description = "TVDB id，必须大于 0；TMDB 不可用时用于兼容回退")
            @RequestParam(name = "tvdb_id", required = false) Integer tvdbId
    ) {
        return ApiResponse.success(resourceSearchService.getSeriesSeasons(tvdbId, tmdbId));
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

    @PostMapping("/series/releases/recommendation")
    @Operation(summary = "推荐剧集发布资源", description = "使用已选剧集的展示标题、原始标题及目标季搜索全部启用索引器，并返回可确认的推荐发布。")
    public ApiResponse<ProwlarrReleaseRecommendationResponse> recommendSeriesRelease(
            @Valid @RequestBody SeriesReleaseRecommendationRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.recommendSeriesRelease(request));
    }

    @PostMapping("/series/releases/recommendation/refresh")
    @Operation(summary = "刷新剧集发布资源推荐", description = "绕过搜索缓存重新执行完整剧集发布搜索计划，全部成功后替换缓存并返回新推荐。")
    public ApiResponse<ProwlarrReleaseRecommendationResponse> refreshSeriesReleaseRecommendation(
            @Valid @RequestBody SeriesReleaseRecommendationRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.refreshSeriesReleaseRecommendation(request));
    }

    @PostMapping("/series/releases/search")
    @Operation(summary = "搜索剧集发布资源", description = "使用与快速添加一致的标题季度搜索计划，合并并去重发布列表。")
    public ApiResponse<ProwlarrReleaseSearchResponse> searchSeriesReleases(
            @Valid @RequestBody SeriesReleaseSearchRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.searchSeriesReleases(request));
    }

    @PostMapping("/series/releases/search/refresh")
    @Operation(summary = "刷新剧集发布资源列表", description = "绕过搜索缓存重新执行完整剧集发布搜索计划，全部成功后替换缓存。")
    public ApiResponse<ProwlarrReleaseSearchResponse> refreshSeriesReleases(
            @Valid @RequestBody SeriesReleaseSearchRequest request
    ) {
        return ApiResponse.success(prowlarrReleaseIngestService.refreshSeriesReleases(request));
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
