package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.resources.response.MediaLibraryPresenceResponse;
import com.medianexus.orchestrator.service.MediaLibraryPresenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/media-library")
@Tag(name = "媒体库检查", description = "创建入库任务前的 Emby 存在性检查")
public class MediaLibraryController {

    private final MediaLibraryPresenceService presenceService;

    public MediaLibraryController(MediaLibraryPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @GetMapping("/presence")
    @Operation(summary = "检查媒体是否已存在", description = "电影按 TMDB，剧集与动漫按 TMDB 和季度检查；命中后任务创建接口会拒绝重复入库。")
    public ApiResponse<MediaLibraryPresenceResponse> checkPresence(
            @Parameter(description = "movie 或 series")
            @RequestParam(name = "media_type") String mediaType,
            @Parameter(description = "TMDB id；手动动漫可改传 bgm_id")
            @RequestParam(name = "tmdb_id", required = false) Integer tmdbId,
            @Parameter(description = "Bangumi id，仅用于手动动漫转换 TMDB")
            @RequestParam(name = "bgm_id", required = false) String bgmId,
            @Parameter(description = "剧集或动漫目标季度")
            @RequestParam(name = "season_number", required = false) Integer seasonNumber
    ) {
        return ApiResponse.success(presenceService.check(mediaType, tmdbId, bgmId, seasonNumber));
    }
}
