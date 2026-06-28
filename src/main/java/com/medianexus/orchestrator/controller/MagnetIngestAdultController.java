package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.request.AdultMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.AdultMagnetIngestTaskResponse;
import com.medianexus.orchestrator.service.AdultMagnetIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/magnet-ingest/adult")
@Tag(name = "Adult 批量下载链接导入", description = "管理员 Adult/JAV/Other magnet/ed2k 批量离线下载、清理和任务日志接口")
public class MagnetIngestAdultController {

    private final AdultMagnetIngestService adultMagnetIngestService;

    public MagnetIngestAdultController(AdultMagnetIngestService adultMagnetIngestService) {
        this.adultMagnetIngestService = adultMagnetIngestService;
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建 Adult 批量下载链接导入任务", description = "仅管理员可用。按分类写入当天目录，并在单条下载完成后整理临时目录。")
    public ApiResponse<AdultMagnetIngestTaskResponse> createTask(
            @Valid @RequestBody AdultMagnetIngestTaskCreateRequest request
    ) {
        return ApiResponse.success(adultMagnetIngestService.createTask(request));
    }

    @GetMapping("/tasks")
    @Operation(summary = "列出最近 Adult 导入任务", description = "仅管理员可用，返回最近创建的 Adult 批量下载链接导入任务。")
    public ApiResponse<AdultMagnetIngestTaskListResponse> listTasks() {
        return ApiResponse.success(adultMagnetIngestService.listTasks());
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "获取 Adult 导入任务详情", description = "仅管理员可用，按任务 id 返回批量任务状态和整理计数。")
    public ApiResponse<AdultMagnetIngestTaskResponse> getTask(
            @Parameter(description = "Adult 导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(adultMagnetIngestService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/logs")
    @Operation(summary = "获取 Adult 导入任务日志", description = "仅管理员可用，返回批量提交、下载、整理和失败阶段日志。")
    public ApiResponse<AdultMagnetIngestTaskLogListResponse> getTaskLogs(
            @Parameter(description = "Adult 导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(adultMagnetIngestService.getTaskLogs(taskId));
    }
}
