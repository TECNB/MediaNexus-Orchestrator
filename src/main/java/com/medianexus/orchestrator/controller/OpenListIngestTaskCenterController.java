package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListManualMagnetRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListAdultBatchRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.OpenListReleaseRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterDetailResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterLogsResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListManualMagnetRetryResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListReleaseRetryContextResponse;
import com.medianexus.orchestrator.service.OpenListIngestTaskCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/task-center/openlist-ingest")
@Tag(name = "任务中心", description = "统一读取 OpenList 入库任务列表")
@Validated
public class OpenListIngestTaskCenterController {

    private final OpenListIngestTaskCenterService taskCenterService;

    public OpenListIngestTaskCenterController(OpenListIngestTaskCenterService taskCenterService) {
        this.taskCenterService = taskCenterService;
    }

    @GetMapping("/tasks")
    @Operation(summary = "列出 OpenList 入库任务", description = "统一返回电影、剧集、动漫整季和有权查看的 Adult 批量任务，并支持状态视图、筛选、搜索和分页。")
    public ApiResponse<OpenListIngestTaskCenterListResponse> listTasks(
            @Parameter(description = "状态视图：ALL、IN_PROGRESS、NEEDS_ATTENTION 或 SUCCEEDED")
            @Pattern(regexp = "(?i)ALL|IN_PROGRESS|NEEDS_ATTENTION|SUCCEEDED", message = "状态视图无效")
            @RequestParam(name = "view", required = false) String view,
            @Parameter(description = "任务产品类别：ALL、MOVIE、SERIES、ANIME 或 ADULT")
            @Pattern(regexp = "(?i)ALL|MOVIE|SERIES|ANIME|ADULT", message = "任务产品类别无效")
            @RequestParam(name = "product_type", required = false) String productType,
            @Parameter(description = "尝试链起点来源：ALL、MANUAL_MAGNET 或 PROWLARR_RELEASE")
            @Pattern(regexp = "(?i)ALL|MANUAL_MAGNET|PROWLARR_RELEASE", message = "任务来源无效")
            @RequestParam(name = "source_type", required = false) String sourceType,
            @Parameter(description = "标题、发布标题或 magnet hash 关键词")
            @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "页码，从 1 开始")
            @Min(value = 1, message = "页码必须大于 0")
            @RequestParam(name = "page", required = false) Integer page,
            @Parameter(description = "每页条数，可选 10、20 或 50；默认 10")
            @Pattern(regexp = "10|20|50", message = "每页条数只能是 10、20 或 50")
            @RequestParam(name = "page_size", required = false) String pageSize
    ) {
        return ApiResponse.success(taskCenterService.listOpenListIngestTasks(
                view,
                productType,
                sourceType,
                keyword,
                page,
                pageSize == null ? null : Integer.valueOf(pageSize)
        ));
    }

    @GetMapping("/tasks/{taskType}/{taskId}")
    @Operation(summary = "读取 OpenList 入库任务详情", description = "统一返回电影、剧集、动漫整季和有权查看的 Adult 批量任务详情、失败证据和首屏日志窗口。")
    public ApiResponse<OpenListIngestTaskCenterDetailResponse> getTaskDetail(
            @Parameter(description = "底层任务类型：movie、series、anime 或 adult")
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @Parameter(description = "底层任务 id")
            @PathVariable String taskId,
            @Parameter(description = "详情内返回的最新日志条数；默认 100，轮询摘要可传 0")
            @Min(value = 0, message = "日志条数不能小于 0")
            @Max(value = 200, message = "日志条数不能大于 200")
            @RequestParam(name = "log_limit", required = false) Integer logLimit
    ) {
        return ApiResponse.success(taskCenterService.getOpenListIngestTaskDetail(taskType, taskId, logLimit));
    }

    @GetMapping("/tasks/{taskType}/{taskId}/logs")
    @Operation(summary = "读取 OpenList 入库任务日志窗口", description = "按日志 id 游标读取最新日志、历史日志或新增日志。before_id 和 after_id 不能同时传。")
    public ApiResponse<OpenListIngestTaskCenterLogsResponse> getTaskLogs(
            @Parameter(description = "底层任务类型：movie、series、anime 或 adult")
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @Parameter(description = "底层任务 id")
            @PathVariable String taskId,
            @Parameter(description = "读取早于该日志 id 的历史日志")
            @Min(value = 1, message = "before_id 必须大于 0")
            @RequestParam(name = "before_id", required = false) Long beforeId,
            @Parameter(description = "读取晚于该日志 id 的新增日志")
            @Min(value = 1, message = "after_id 必须大于 0")
            @RequestParam(name = "after_id", required = false) Long afterId,
            @Parameter(description = "日志条数，默认 100，最大 200")
            @Min(value = 1, message = "日志条数不能小于 1")
            @Max(value = 200, message = "日志条数不能大于 200")
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return ApiResponse.success(taskCenterService.getOpenListIngestTaskLogs(
                taskType,
                taskId,
                beforeId,
                afterId,
                limit
        ));
    }

    @GetMapping("/tasks/{taskType}/{taskId}/release-retry-context")
    @Operation(summary = "读取发布资源重试上下文", description = "支持可恢复终态的电影、剧集和共享 SERIES 动漫整季任务，不限制当前尝试来源。")
    public ApiResponse<OpenListReleaseRetryContextResponse> getReleaseRetryContext(
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @PathVariable String taskId
    ) {
        return ApiResponse.success(taskCenterService.getReleaseRetryContext(taskType, taskId));
    }

    @PostMapping("/tasks/{taskType}/{taskId}/release-retries")
    @Operation(summary = "选择新发布资源创建重试尝试", description = "从原任务继承媒体上下文、产品类别与尝试链，原任务保持不变。")
    public ApiResponse<OpenListManualMagnetRetryResponse> retryWithSelectedRelease(
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @PathVariable String taskId,
            @Valid @RequestBody OpenListReleaseRetryRequest request
    ) {
        return ApiResponse.success(taskCenterService.retryWithSelectedRelease(taskType, taskId, request));
    }

    @PostMapping("/tasks/{taskType}/{taskId}/manual-magnet-retries/reuse-original")
    @Operation(summary = "沿用当前 magnet 创建重试尝试", description = "支持可恢复终态下的电影、剧集和动漫任务；新尝试保留尝试链，原任务保持不变。")
    public ApiResponse<OpenListManualMagnetRetryResponse> reuseOriginalManualMagnet(
            @Parameter(description = "底层任务类型：movie、series 或 anime")
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @Parameter(description = "底层任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(taskCenterService.reuseOriginalManualMagnet(taskType, taskId));
    }

    @PostMapping("/tasks/{taskType}/{taskId}/manual-magnet-retries/replace-magnet")
    @Operation(summary = "更换 magnet 创建重试尝试", description = "支持可恢复终态下的电影、剧集和动漫手动 magnet 任务，以及发布资源任务的手动兜底。")
    public ApiResponse<OpenListManualMagnetRetryResponse> replaceManualMagnet(
            @Parameter(description = "底层任务类型：movie、series 或 anime")
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @Parameter(description = "底层任务 id")
            @PathVariable String taskId,
            @Valid @RequestBody OpenListManualMagnetRetryRequest request
    ) {
        return ApiResponse.success(taskCenterService.replaceManualMagnet(taskType, taskId, request));
    }

    @PostMapping("/tasks/adult/{taskId}/batch-retries")
    @Operation(summary = "整批重新提交 Adult 下载链接", description = "仅支持可恢复终态的 Adult 任务；创建关联的新任务尝试，原任务保持不变。")
    public ApiResponse<OpenListManualMagnetRetryResponse> retryAdultBatch(
            @Parameter(description = "Adult 底层任务 id")
            @PathVariable String taskId,
            @Valid @RequestBody OpenListAdultBatchRetryRequest request
    ) {
        return ApiResponse.success(taskCenterService.retryAdultBatch(taskId, request));
    }
}
