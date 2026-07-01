package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.taskcenter.response.OpenListIngestTaskCenterListResponse;
import com.medianexus.orchestrator.service.OpenListIngestTaskCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/task-center/openlist-ingest")
@Tag(name = "任务中心", description = "统一读取 OpenList 入库任务列表")
public class OpenListIngestTaskCenterController {

    private final OpenListIngestTaskCenterService taskCenterService;

    public OpenListIngestTaskCenterController(OpenListIngestTaskCenterService taskCenterService) {
        this.taskCenterService = taskCenterService;
    }

    @GetMapping("/tasks")
    @Operation(summary = "列出 OpenList 入库任务", description = "统一返回电影、剧集和动漫整季任务，按更新时间倒序排列。")
    public ApiResponse<OpenListIngestTaskCenterListResponse> listTasks() {
        return ApiResponse.success(taskCenterService.listOpenListIngestTasks());
    }
}
