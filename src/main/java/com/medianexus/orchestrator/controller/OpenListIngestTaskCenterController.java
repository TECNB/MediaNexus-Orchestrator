package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterDetailResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.service.OpenListIngestTaskCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @Parameter(description = "任务来源：ALL、MANUAL_MAGNET 或 PROWLARR_RELEASE")
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
    @Operation(summary = "读取 OpenList 入库任务详情", description = "统一返回电影、剧集、动漫整季和有权查看的 Adult 批量任务详情、失败证据和完整日志。")
    public ApiResponse<OpenListIngestTaskCenterDetailResponse> getTaskDetail(
            @Parameter(description = "底层任务类型：movie、series、anime 或 adult")
            @Pattern(regexp = "(?i)movie|series|anime|adult", message = "任务类型无效")
            @PathVariable String taskType,
            @Parameter(description = "底层任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(taskCenterService.getOpenListIngestTaskDetail(taskType, taskId));
    }
}
