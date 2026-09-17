package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.BackfillRequest;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ConfigResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ConfigUpdateRequest;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.OverviewResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ResolveSourceRequest;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.ResolvedSourceResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.RunListResponse;
import com.medianexus.orchestrator.dto.telegram.TelegramAutomationContract.RunResponse;
import com.medianexus.orchestrator.service.TelegramAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/telegram-automation")
@Tag(name = "Telegram 自动化", description = "配置来源频道并编排 Telegram Worker 转发")
@Validated
public class AdminTelegramAutomationController {

    private final TelegramAutomationService automationService;

    public AdminTelegramAutomationController(TelegramAutomationService automationService) {
        this.automationService = automationService;
    }

    @GetMapping
    @Operation(summary = "读取 Telegram 自动化总览")
    public ApiResponse<OverviewResponse> overview() {
        return ApiResponse.success(automationService.overview());
    }

    @PutMapping("/config")
    @Operation(summary = "保存 Telegram 自动化配置")
    public ApiResponse<ConfigResponse> updateConfig(@Valid @RequestBody ConfigUpdateRequest request) {
        return ApiResponse.success(automationService.updateConfig(request));
    }

    @PostMapping("/sources/resolve")
    @Operation(summary = "解析 Telegram 来源频道")
    public ApiResponse<ResolvedSourceResponse> resolveSource(@Valid @RequestBody ResolveSourceRequest request) {
        return ApiResponse.success(automationService.resolveSource(request.sourceRef()));
    }

    @PostMapping("/runs/follow/dry-run")
    @Operation(summary = "试运行所有已启用频道的追更")
    public ApiResponse<RunResponse> followDryRun() {
        return ApiResponse.success(automationService.requestFollowDryRun());
    }

    @PostMapping("/runs/follow")
    @Operation(summary = "立即运行所有已启用频道的追更")
    public ApiResponse<RunResponse> follow() {
        return ApiResponse.success(automationService.requestFollowExecution());
    }

    @PostMapping("/channels/{channelId}/backfill/dry-run")
    @Operation(summary = "试运行单个频道的历史回溯")
    public ApiResponse<RunResponse> backfillDryRun(
            @PathVariable String channelId,
            @Valid @RequestBody BackfillRequest request
    ) {
        return ApiResponse.success(automationService.requestBackfillDryRun(channelId, request));
    }

    @PostMapping("/channels/{channelId}/backfill")
    @Operation(summary = "运行单个频道的历史回溯")
    public ApiResponse<RunResponse> backfill(
            @PathVariable String channelId,
            @Valid @RequestBody BackfillRequest request
    ) {
        return ApiResponse.success(automationService.requestBackfillExecution(channelId, request));
    }

    @GetMapping("/runs")
    @Operation(summary = "分页读取 Telegram 自动化运行记录")
    public ApiResponse<RunListResponse> listRuns(
            @Min(value = 1, message = "页码必须大于 0")
            @RequestParam(name = "page", required = false) Integer page,
            @Min(value = 1, message = "每页条数必须大于 0")
            @Max(value = 50, message = "每页条数不能大于 50")
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(automationService.listRuns(page, pageSize));
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "读取 Telegram 自动化运行详情")
    public ApiResponse<RunResponse> getRun(@PathVariable String runId) {
        return ApiResponse.success(automationService.getRun(runId));
    }
}
