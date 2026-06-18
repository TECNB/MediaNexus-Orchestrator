package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskListResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskLogListResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.service.MagnetIngestService;
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
@RequestMapping("/api/v1/magnet-ingest")
@Tag(name = "电影剧集磁力导入", description = "电影和剧集 magnet 离线下载、后台整理和任务日志接口")
public class MagnetIngestController {

    private final MagnetIngestService magnetIngestService;

    public MagnetIngestController(MagnetIngestService magnetIngestService) {
        this.magnetIngestService = magnetIngestService;
    }

    @PostMapping("/movies")
    @Operation(summary = "创建电影 magnet 导入任务", description = "创建后台任务，提交 OpenList 离线下载并在完成后整理电影文件和字幕。")
    public ApiResponse<MovieMagnetIngestTaskResponse> ingestMovie(
            @Valid @RequestBody MovieMagnetIngestRequest request
    ) {
        return ApiResponse.success(magnetIngestService.createMovieTask(request));
    }

    @PostMapping("/movies/tasks")
    @Operation(summary = "创建电影 magnet 导入任务", description = "与 /movies 兼容入口相同，返回任务状态和日志查询 id。")
    public ApiResponse<MovieMagnetIngestTaskResponse> createMovieTask(
            @Valid @RequestBody MovieMagnetIngestRequest request
    ) {
        return ApiResponse.success(magnetIngestService.createMovieTask(request));
    }

    @GetMapping("/movies/tasks")
    @Operation(summary = "列出最近电影导入任务", description = "返回最近创建的电影 magnet 导入任务，当前固定最多 20 条。")
    public ApiResponse<MovieMagnetIngestTaskListResponse> listMovieTasks() {
        return ApiResponse.success(magnetIngestService.listMovieTasks());
    }

    @GetMapping("/movies/tasks/{taskId}")
    @Operation(summary = "获取电影导入任务详情", description = "按任务 id 返回状态、阶段、目标路径、整理数量和错误信息。")
    public ApiResponse<MovieMagnetIngestTaskResponse> getMovieTask(
            @Parameter(description = "导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(magnetIngestService.getMovieTask(taskId));
    }

    @GetMapping("/movies/tasks/{taskId}/logs")
    @Operation(summary = "获取电影导入任务日志", description = "按任务 id 返回创建、提交、下载、整理和失败阶段的任务日志。")
    public ApiResponse<MovieMagnetIngestTaskLogListResponse> getMovieTaskLogs(
            @Parameter(description = "导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(magnetIngestService.getMovieTaskLogs(taskId));
    }

    @PostMapping("/series")
    @Operation(summary = "创建剧集 magnet 导入任务", description = "创建后台任务，提交 OpenList 离线下载并在完成后整理剧集视频和字幕。")
    public ApiResponse<SeriesMagnetIngestTaskResponse> ingestSeries(
            @Valid @RequestBody SeriesMagnetIngestRequest request
    ) {
        return ApiResponse.success(magnetIngestService.createSeriesTask(request));
    }

    @PostMapping("/series/tasks")
    @Operation(summary = "创建剧集 magnet 导入任务", description = "与 /series 兼容入口相同，返回任务状态和日志查询 id。")
    public ApiResponse<SeriesMagnetIngestTaskResponse> createSeriesTask(
            @Valid @RequestBody SeriesMagnetIngestRequest request
    ) {
        return ApiResponse.success(magnetIngestService.createSeriesTask(request));
    }

    @GetMapping("/series/tasks")
    @Operation(summary = "列出最近剧集导入任务", description = "返回最近创建的剧集 magnet 导入任务，当前固定最多 20 条。")
    public ApiResponse<SeriesMagnetIngestTaskListResponse> listSeriesTasks() {
        return ApiResponse.success(magnetIngestService.listSeriesTasks());
    }

    @GetMapping("/series/tasks/{taskId}")
    @Operation(summary = "获取剧集导入任务详情", description = "按任务 id 返回状态、阶段、目标路径、整理数量和错误信息。")
    public ApiResponse<SeriesMagnetIngestTaskResponse> getSeriesTask(
            @Parameter(description = "导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(magnetIngestService.getSeriesTask(taskId));
    }

    @GetMapping("/series/tasks/{taskId}/logs")
    @Operation(summary = "获取剧集导入任务日志", description = "按任务 id 返回创建、提交、下载、整理和失败阶段的任务日志。")
    public ApiResponse<SeriesMagnetIngestTaskLogListResponse> getSeriesTaskLogs(
            @Parameter(description = "导入任务 id")
            @PathVariable String taskId
    ) {
        return ApiResponse.success(magnetIngestService.getSeriesTaskLogs(taskId));
    }
}
