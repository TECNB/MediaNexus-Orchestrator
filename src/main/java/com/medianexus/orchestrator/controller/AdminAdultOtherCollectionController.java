package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.emby.request.AdultOtherCollectionSyncRequest;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationRunResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionInventoryResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncRunResponse;
import com.medianexus.orchestrator.service.AdultOtherCollectionSyncService;
import com.medianexus.orchestrator.service.AdultOtherAutomationRunRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/emby/adult-other-collections")
@Tag(name = "Adult-Other 合集同步", description = "管理员将 Adult - Other 外层资源文件夹同步为 Emby Collection")
public class AdminAdultOtherCollectionController {

    private final AdultOtherCollectionSyncService syncService;
    private final AdultOtherAutomationRunRecorder automationRunRecorder;

    public AdminAdultOtherCollectionController(
            AdultOtherCollectionSyncService syncService,
            AdultOtherAutomationRunRecorder automationRunRecorder
    ) {
        this.syncService = syncService;
        this.automationRunRecorder = automationRunRecorder;
    }

    @GetMapping("/runs/latest")
    @Operation(summary = "读取最近一次 Adult-Other 合集同步结果")
    public ApiResponse<AdultOtherCollectionSyncRunResponse> getLatestRun() {
        return ApiResponse.success(syncService.latestRun());
    }

    @GetMapping("/automation/runs")
    @Operation(summary = "读取 Adult-Other 最近自动化运行记录")
    public ApiResponse<List<AdultOtherAutomationRunResponse>> getAutomationRuns(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.success(automationRunRecorder.recent(limit));
    }

    @GetMapping("/automation/runs/{runId}")
    @Operation(summary = "读取 Adult-Other 自动化运行媒体与合集明细")
    public ApiResponse<AdultOtherAutomationRunResponse> getAutomationRunDetails(
            @PathVariable String runId
    ) {
        return ApiResponse.success(automationRunRecorder.details(runId));
    }

    @GetMapping("/source-folders")
    @Operation(summary = "读取 Adult-Other 可同步文件夹")
    public ApiResponse<AdultOtherCollectionInventoryResponse> getSourceFolders() {
        return ApiResponse.success(syncService.collectionInventory());
    }

    @PostMapping("/preview")
    @Operation(summary = "预览 Adult-Other 合集同步计划", description = "只读取 Emby 媒体项和已有 Collection，不创建或修改 Collection。")
    public ApiResponse<AdultOtherCollectionSyncRunResponse> preview(
            @Valid @RequestBody(required = false) AdultOtherCollectionSyncRequest request
    ) {
        return ApiResponse.success(syncService.preview(request));
    }

    @PostMapping("/sync")
    @Operation(summary = "执行 Adult-Other 合集同步", description = "创建缺失 Collection，并向已有 Collection 补充缺失媒体项；不会移除已有成员。")
    public ApiResponse<AdultOtherCollectionSyncRunResponse> sync(
            @Valid @RequestBody(required = false) AdultOtherCollectionSyncRequest request
    ) {
        return ApiResponse.success(syncService.sync(request));
    }

    @PostMapping("/cleanup-preview")
    @Operation(summary = "预览 Adult-Other 空 Collection 清理计划", description = "只检查指定同步范围上次关联的 Collection，不删除任何内容。")
    public ApiResponse<AdultOtherCollectionSyncRunResponse> cleanupPreview(
            @Valid @RequestBody(required = false) AdultOtherCollectionSyncRequest request
    ) {
        return ApiResponse.success(syncService.cleanupPreview(request));
    }

    @PostMapping("/cleanup")
    @Operation(summary = "执行 Adult-Other 空 Collection 清理", description = "仅删除指定同步范围内当前成员数为 0 的 Collection；非空 Collection 会跳过并标记复查。")
    public ApiResponse<AdultOtherCollectionSyncRunResponse> cleanup(
            @Valid @RequestBody(required = false) AdultOtherCollectionSyncRequest request
    ) {
        return ApiResponse.success(syncService.cleanup(request));
    }
}
