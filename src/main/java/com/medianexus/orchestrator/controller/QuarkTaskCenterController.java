package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.taskcenter.request.QuarkTaskCenterRetryRequest;
import com.medianexus.orchestrator.dto.taskcenter.request.QuarkTaskCenterSubscriptionRequest;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterActionResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterDetailResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterListResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.QuarkTaskCenterLogsResponse;
import com.medianexus.orchestrator.service.QuarkTaskCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/task-center/quark-ingest")
@Tag(name = "任务中心", description = "查看和恢复 Quark 入库任务")
@Validated
public class QuarkTaskCenterController {

    private final QuarkTaskCenterService taskCenterService;

    public QuarkTaskCenterController(QuarkTaskCenterService taskCenterService) {
        this.taskCenterService = taskCenterService;
    }

    @GetMapping("/tasks")
    @Operation(summary = "列出 Quark 入库任务")
    public ApiResponse<QuarkTaskCenterListResponse> listTasks(
            @Parameter(description = "状态视图：ALL、IN_PROGRESS、NEEDS_ATTENTION 或 SUCCEEDED")
            @Pattern(regexp = "(?i)ALL|IN_PROGRESS|NEEDS_ATTENTION|SUCCEEDED", message = "状态视图无效")
            @RequestParam(name = "view", required = false) String view,
            @Parameter(description = "媒体类型：ALL、MOVIE、SERIES 或 VARIETY")
            @Pattern(regexp = "(?i)ALL|MOVIE|SERIES|VARIETY", message = "媒体类型无效")
            @RequestParam(name = "product_type", required = false) String productType,
            @Parameter(description = "来源：ALL、MANUAL_QUARK 或 PANSOU_SEARCH")
            @Pattern(regexp = "(?i)ALL|MANUAL_QUARK|PANSOU_SEARCH", message = "来源无效")
            @RequestParam(name = "source_type", required = false) String sourceType,
            @Parameter(description = "订阅筛选：ALL、SUBSCRIBED 或 ONE_TIME")
            @Pattern(regexp = "(?i)ALL|SUBSCRIBED|ONE_TIME", message = "订阅筛选无效")
            @RequestParam(name = "subscription", required = false) String subscription,
            @RequestParam(name = "keyword", required = false) String keyword,
            @Min(value = 1, message = "页码必须大于 0")
            @RequestParam(name = "page", required = false) Integer page,
            @Pattern(regexp = "10|20|50", message = "每页条数只能是 10、20 或 50")
            @RequestParam(name = "page_size", required = false) String pageSize
    ) {
        return ApiResponse.success(taskCenterService.listTasks(
                view, productType, sourceType, subscription, keyword, page,
                pageSize == null ? null : Integer.valueOf(pageSize)));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "读取 Quark 入库任务详情")
    public ApiResponse<QuarkTaskCenterDetailResponse> getTaskDetail(
            @PathVariable String taskId,
            @Min(value = 0, message = "日志条数不能小于 0")
            @Max(value = 200, message = "日志条数不能大于 200")
            @RequestParam(name = "log_limit", required = false) Integer logLimit
    ) {
        return ApiResponse.success(taskCenterService.getTaskDetail(taskId, logLimit));
    }

    @GetMapping("/tasks/{taskId}/logs")
    @Operation(summary = "读取 Quark 入库任务日志窗口")
    public ApiResponse<QuarkTaskCenterLogsResponse> getTaskLogs(
            @PathVariable String taskId,
            @Min(value = 1, message = "before_id 必须大于 0")
            @RequestParam(name = "before_id", required = false) Long beforeId,
            @Min(value = 1, message = "after_id 必须大于 0")
            @RequestParam(name = "after_id", required = false) Long afterId,
            @Min(value = 1, message = "日志条数必须大于 0")
            @Max(value = 200, message = "日志条数不能大于 200")
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return ApiResponse.success(taskCenterService.getTaskLogs(taskId, beforeId, afterId, limit));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @Operation(summary = "重试 Quark 未完成处理项")
    public ApiResponse<QuarkTaskCenterActionResponse> retry(
            @PathVariable String taskId,
            @Valid @RequestBody(required = false) QuarkTaskCenterRetryRequest request
    ) {
        return ApiResponse.success(taskCenterService.retry(taskId, request));
    }

    @PatchMapping("/tasks/{taskId}/children/{childId}/subscription")
    @Operation(summary = "更新 Quark 执行单元的自动更新开关")
    public ApiResponse<QuarkTaskCenterActionResponse> updateSubscription(
            @PathVariable String taskId,
            @PathVariable String childId,
            @Valid @RequestBody QuarkTaskCenterSubscriptionRequest request
    ) {
        return ApiResponse.success(taskCenterService.updateSubscription(taskId, childId, request));
    }
}
