package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.javdb.request.JavdbAutomationConfigUpdateRequest;
import com.medianexus.orchestrator.dto.javdb.request.JavdbCookieUpdateRequest;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationConfigResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationOverviewResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationRunListResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbAutomationRunResponse;
import com.medianexus.orchestrator.dto.javdb.response.JavdbCredentialStatusResponse;
import com.medianexus.orchestrator.service.JavdbAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/v1/admin/javdb-automation")
@Tag(name = "JAVDB 自动化", description = "管理员读取 JAVDB 有码榜单并创建 Adult-JAV 入库任务")
@Validated
public class AdminJavdbAutomationController {

    private final JavdbAutomationService automationService;

    public AdminJavdbAutomationController(JavdbAutomationService automationService) {
        this.automationService = automationService;
    }

    @GetMapping
    @Operation(summary = "读取 JAVDB 自动化总览")
    public ApiResponse<JavdbAutomationOverviewResponse> overview() {
        return ApiResponse.success(automationService.overview());
    }

    @PutMapping("/config")
    @Operation(summary = "更新 JAVDB 自动化配置", description = "启用前必须完成 Cookie 验证、Emby Adult-JAV 检查和一次成功试运行。")
    public ApiResponse<JavdbAutomationConfigResponse> updateConfig(
            @Valid @RequestBody JavdbAutomationConfigUpdateRequest request
    ) {
        return ApiResponse.success(automationService.updateConfig(request));
    }

    @PutMapping("/credential")
    @Operation(summary = "覆盖 JAVDB Cookie", description = "保存后立即访问日榜验证；响应只返回验证状态，不返回 Cookie 原文。")
    public ApiResponse<JavdbCredentialStatusResponse> updateCredential(
            @Valid @RequestBody JavdbCookieUpdateRequest request
    ) {
        return ApiResponse.success(automationService.updateCookie(request));
    }

    @PostMapping("/runs/dry-run")
    @Operation(summary = "试运行 JAVDB 自动化", description = "抓取、查重并选择磁力，但不会创建 Adult 任务。")
    public ApiResponse<JavdbAutomationRunResponse> dryRun() {
        return ApiResponse.success(automationService.requestDryRun());
    }

    @PostMapping("/runs")
    @Operation(summary = "立即运行 JAVDB 自动化", description = "重新抓取榜单和查重后创建 Adult-JAV 批量任务。")
    public ApiResponse<JavdbAutomationRunResponse> execute() {
        return ApiResponse.success(automationService.requestExecution());
    }

    @GetMapping("/runs")
    @Operation(summary = "分页读取 JAVDB 自动化运行记录")
    public ApiResponse<JavdbAutomationRunListResponse> listRuns(
            @Parameter(description = "页码，从 1 开始")
            @Min(value = 1, message = "页码必须大于 0")
            @RequestParam(name = "page", required = false) Integer page,
            @Parameter(description = "每页条数，默认 20，最大 50")
            @Min(value = 1, message = "每页条数必须大于 0")
            @Max(value = 50, message = "每页条数不能大于 50")
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(automationService.listRuns(page, pageSize));
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "读取 JAVDB 自动化运行详情", description = "返回数量漏斗、榜单出现位置、磁力候选、选择原因和下游 Adult 任务。")
    public ApiResponse<JavdbAutomationRunResponse> getRun(
            @Parameter(description = "自动化运行 id")
            @PathVariable String runId
    ) {
        return ApiResponse.success(automationService.getRun(runId));
    }
}
