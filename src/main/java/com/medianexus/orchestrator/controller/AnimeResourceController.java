package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.anime.request.AnimeSubscriptionPreviewRequest;
import com.medianexus.orchestrator.dto.anime.response.AnimeSearchResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubtitleGroupsResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubscriptionPreviewResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubscriptionResponse;
import com.medianexus.orchestrator.service.AnimeSearchService;
import com.medianexus.orchestrator.service.AnimeSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources/anime")
@Tag(name = "动漫资源", description = "面向前端资源页的 Mikan 搜索、字幕组选择和 Ani-RSS 订阅预览接口")
public class AnimeResourceController {

    private final AnimeSearchService animeSearchService;
    private final AnimeSubscriptionService animeSubscriptionService;

    public AnimeResourceController(
            AnimeSearchService animeSearchService,
            AnimeSubscriptionService animeSubscriptionService
    ) {
        this.animeSearchService = animeSearchService;
        this.animeSubscriptionService = animeSubscriptionService;
    }

    @GetMapping("/search")
    @Operation(summary = "搜索 Mikan 番剧", description = "按关键词从 Ani-RSS 的 Mikan 搜索能力拉取展示用番剧条目。")
    public ApiResponse<AnimeSearchResponse> search(
            @Parameter(description = "搜索关键词，不能为空")
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(animeSearchService.search(term));
    }

    @GetMapping("/groups")
    @Operation(summary = "获取字幕组候选", description = "根据 Mikan 条目来源地址加载可订阅字幕组，并按语言、条目数量和更新时间排序。")
    public ApiResponse<AnimeSubtitleGroupsResponse> groups(
            @Parameter(description = "Mikan 番剧页面地址")
            @RequestParam(name = "sourceUrl", required = false) String sourceUrl
    ) {
        return ApiResponse.success(animeSubscriptionService.groups(sourceUrl));
    }

    @GetMapping("/{id}/groups")
    @Operation(summary = "获取指定条目的字幕组候选", description = "路径 id 用于前端路由语义，实际查询仍以 sourceUrl 作为 Ani-RSS 上游参数。")
    public ApiResponse<AnimeSubtitleGroupsResponse> groupsForItem(
            @Parameter(description = "前端条目标识，例如 mikan:1234")
            @PathVariable String id,
            @Parameter(description = "Mikan 番剧页面地址")
            @RequestParam(name = "sourceUrl", required = false) String sourceUrl
    ) {
        return ApiResponse.success(animeSubscriptionService.groups(sourceUrl));
    }

    @PostMapping("/preview")
    @Operation(summary = "预览 Ani-RSS 订阅", description = "将用户选择的字幕组转换为 Ani-RSS 订阅草稿，并返回预览集数和缺集信息。")
    public ApiResponse<AnimeSubscriptionPreviewResponse> preview(@RequestBody AnimeSubscriptionPreviewRequest request) {
        return ApiResponse.success(animeSubscriptionService.preview(request));
    }

    @PostMapping("/subscribe")
    @Operation(summary = "创建 Ani-RSS 订阅", description = "提交前会先预览并检查同名同季订阅，重复时返回 exists 而不是重复创建。")
    public ApiResponse<AnimeSubscriptionResponse> subscribe(@RequestBody AnimeSubscriptionPreviewRequest request) {
        return ApiResponse.success(animeSubscriptionService.subscribe(request));
    }
}
