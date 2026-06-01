package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetIngestTaskCreateRequest;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetSearchResponse;
import com.medianexus.orchestrator.service.AnimeMagnetIngestTaskService;
import com.medianexus.orchestrator.service.AnimeMagnetSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/magnet-ingest/anime")
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
    public ApiResponse<AnimeMagnetSearchResponse> search(
            @RequestParam(name = "term", required = false) String term
    ) {
        return ApiResponse.success(animeMagnetSearchService.search(term));
    }

    @PostMapping("/tasks")
    public ApiResponse<AnimeMagnetIngestTaskResponse> createTask(
            @RequestBody AnimeMagnetIngestTaskCreateRequest request
    ) {
        return ApiResponse.success(animeMagnetIngestTaskService.createTask(request));
    }

    @GetMapping("/tasks")
    public ApiResponse<AnimeMagnetIngestTaskListResponse> listTasks() {
        return ApiResponse.success(animeMagnetIngestTaskService.listTasks());
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AnimeMagnetIngestTaskResponse> getTask(@PathVariable String taskId) {
        return ApiResponse.success(animeMagnetIngestTaskService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/logs")
    public ApiResponse<AnimeMagnetIngestTaskLogListResponse> getTaskLogs(@PathVariable String taskId) {
        return ApiResponse.success(animeMagnetIngestTaskService.getTaskLogs(taskId));
    }
}
