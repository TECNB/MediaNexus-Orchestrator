package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyWatchRankingResponse;
import com.medianexus.orchestrator.service.EmbyWatchRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/emby")
@Tag(name = "Emby 观看统计", description = "管理员查看 Emby 用户活跃和作品观看排行")
public class AdminEmbyWatchRankingController {

    private final EmbyWatchRankingService rankingService;

    public AdminEmbyWatchRankingController(EmbyWatchRankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping("/watch-rankings")
    @Operation(summary = "查看 Emby 观看活跃与排行", description = "按北京时间自然日统计用户、电影和电视剧/番剧观看排行。")
    public ApiResponse<EmbyWatchRankingResponse> getWatchRankings(
            @Parameter(description = "统计日期，格式 yyyy-MM-dd；不传时使用北京时间今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "每个榜单返回条数；不传或小于 1 时默认为 20")
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return ApiResponse.success(rankingService.getRankings(date, limit));
    }
}
