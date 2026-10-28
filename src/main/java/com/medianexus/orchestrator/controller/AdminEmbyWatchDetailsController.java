package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyWatchDetails;
import com.medianexus.orchestrator.service.EmbyWatchDetailsQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/emby/watch-rankings")
@Tag(name = "Emby 观看明细", description = "管理员按同一统计范围浏览全部排行、观看作品、会话和观看人")
public class AdminEmbyWatchDetailsController {
    private final EmbyWatchDetailsQuery query;

    public AdminEmbyWatchDetailsController(EmbyWatchDetailsQuery query) { this.query = query; }

    @GetMapping("/users")
    @Operation(summary = "用户已结算观看排行")
    public ApiResponse<EmbyWatchDetails.Page<EmbyWatchDetails.User>> users(
            @Parameter(description = "统计周期 day 或 month；默认 day")
            @RequestParam(name = "period", defaultValue = "day") String period,
            @Parameter(description = "北京时间统计日 yyyy-MM-dd；不传为今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "统计月份 yyyy-MM；不传为当前月")
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(query.users(period, date, month, page, pageSize));
    }

    @GetMapping("/users/{userId}/works")
    @Operation(summary = "用户全部已结算作品")
    public ApiResponse<EmbyWatchDetails.Page<EmbyWatchDetails.Work>> userWorks(
            @PathVariable String userId,
            @Parameter(description = "统计周期 day 或 month；默认 day")
            @RequestParam(name = "period", defaultValue = "day") String period,
            @Parameter(description = "北京时间统计日 yyyy-MM-dd；不传为今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "统计月份 yyyy-MM；不传为当前月")
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(query.userWorks(userId, period, date, month, page, pageSize));
    }

    @GetMapping("/users/{userId}/sessions")
    @Operation(summary = "用户逐次播放与进行中估算")
    public ApiResponse<EmbyWatchDetails.Page<EmbyWatchDetails.Session>> sessions(
            @PathVariable String userId,
            @RequestParam(name = "media_id", required = false) String mediaId,
            @Parameter(description = "按作品筛选时同时传分类，保持与作品汇总一致")
            @RequestParam(name = "category", required = false) String category,
            @Parameter(description = "统计周期 day 或 month；默认 day")
            @RequestParam(name = "period", defaultValue = "day") String period,
            @Parameter(description = "北京时间统计日 yyyy-MM-dd；不传为今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "统计月份 yyyy-MM；不传为当前月")
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(query.sessions(userId, mediaId, category, period, date, month, page, pageSize));
    }

    @GetMapping("/media")
    @Operation(summary = "按完整类别排序的作品排行")
    public ApiResponse<EmbyWatchDetails.Page<EmbyWatchDetails.Work>> media(
            @Parameter(description = "作品分类 movie、series、adult 或 unknown")
            @RequestParam(name = "category") String category,
            @Parameter(description = "按 duration 时长、viewers 人数或 plays 次数排序，默认时长")
            @RequestParam(name = "sort", defaultValue = "duration") String sort,
            @Parameter(description = "统计周期 day 或 month；默认 day")
            @RequestParam(name = "period", defaultValue = "day") String period,
            @Parameter(description = "北京时间统计日 yyyy-MM-dd；不传为今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "统计月份 yyyy-MM；不传为当前月")
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(query.media(category, sort, period, date, month, page, pageSize));
    }

    @GetMapping("/viewers")
    @Operation(summary = "作品观看用户及各自时长")
    public ApiResponse<EmbyWatchDetails.Page<EmbyWatchDetails.Viewer>> viewers(
            @RequestParam(name = "media_id") String mediaId,
            @Parameter(description = "作品分类，与作品汇总使用同一身份")
            @RequestParam(name = "category") String category,
            @Parameter(description = "统计周期 day 或 month；默认 day")
            @RequestParam(name = "period", defaultValue = "day") String period,
            @Parameter(description = "北京时间统计日 yyyy-MM-dd；不传为今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "统计月份 yyyy-MM；不传为当前月")
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(query.viewers(mediaId, category, period, date, month, page, pageSize));
    }

    @GetMapping("/activity")
    @Operation(summary = "进行中及待确认的观看动向")
    public ApiResponse<EmbyWatchDetails.Page<EmbyWatchDetails.Session>> activity(
            @Parameter(description = "统计周期 day 或 month；默认 day")
            @RequestParam(name = "period", defaultValue = "day") String period,
            @Parameter(description = "北京时间统计日 yyyy-MM-dd；不传为今天")
            @RequestParam(name = "date", required = false) String date,
            @Parameter(description = "统计月份 yyyy-MM；不传为当前月")
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(query.activity(period, date, month, page, pageSize));
    }
}
