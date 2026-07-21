package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.request.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.AnimeMagnetSearchResponse;
import com.medianexus.orchestrator.service.AnimeMagnetIngestTaskService;
import com.medianexus.orchestrator.service.AnimeMagnetSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/magnet-ingest/anime")
@Tag(name = "动漫整季磁力导入", description = "整季 magnet 离线下载、OpenList 文件整理和任务日志接口；历史 Bangumi 搜索入口继续保留。")
public class MagnetIngestAnimeController {

    private final AnimeMagnetSearchService animeMagnetSearchService;
    private final AnimeMagnetIngestTaskService animeMagnetIngestTaskService;

    public MagnetIngestAnimeController(
            AnimeMagnetSearchService animeMagnetSearchService,
            AnimeMagnetIngestTaskService animeMagnetIngestTaskService
    ) {
        this.animeMagnetSearchService = animeMagnetSearchService;
        this.animeMagnetIngestTaskService = animeMagnetIngestTaskService;
    }

    @GetMapping("/search")
    @Operation(summary = "搜索 Bangumi 条目", description = "按关键词从 Ani-RSS 的 Bangumi 搜索能力获取整季导入需要的条目信息。")
    public ApiResponse<AnimeMagnetSearchResponse> search(
            @Parameter(description = "Bangumi 搜索关键词，不能为空")
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(animeMagnetSearchService.search(term));
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建整季 magnet 导入任务", description = "同一 btih hash 存在未完成任务时返回已有任务，避免重复提交 OpenList 离线下载。")
    public ApiResponse<AnimeMagnetIngestTaskResponse> createTask(
            @Valid @RequestBody AnimeMagnetIngestTaskCreateRequest request
    ) {
        Integer tmdbId = request.tmdbId();
        if ((tmdbId == null || tmdbId <= 0) && StringUtils.hasText(request.bgmId())) {
            tmdbId = animeMagnetSearchService.resolveTmdbId(request.bgmId());
        }
        return ApiResponse.success(animeMagnetIngestTaskService.createTask(request, tmdbId));
    }

    @GetMapping("/tasks")
    @Operation(summary = "列出最近导入任务", description = "返回最近创建的整季 magnet 导入任务，当前固定最多 20 条。")
    public ApiResponse<AnimeMagnetIngestTaskListResponse> listTasks() {
        return ApiResponse.success(animeMagnetIngestTaskService.listTasks());
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "获取导入任务详情", description = "按任务 id 返回状态、阶段、目标路径、整理数量和错误信息。")
    public ApiResponse<AnimeMagnetIngestTaskResponse> getTask(
            @Parameter(description = "导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(animeMagnetIngestTaskService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/logs")
    @Operation(summary = "获取导入任务日志", description = "按任务 id 返回创建、提交、下载、整理和失败阶段的任务日志。")
    public ApiResponse<AnimeMagnetIngestTaskLogListResponse> getTaskLogs(
            @Parameter(description = "导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(animeMagnetIngestTaskService.getTaskLogs(taskId));
    }
}
