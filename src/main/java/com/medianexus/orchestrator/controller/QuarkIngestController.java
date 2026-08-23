package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.quark.request.MovieQuarkIngestRequest;
import com.medianexus.orchestrator.dto.quark.request.SeriesQuarkIngestRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestTaskResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestPreviewResponse;
import com.medianexus.orchestrator.service.QuarkIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quark-ingest")
@Tag(name = "Quark 分享链接入库", description = "通过 QAS 创建电影、电视剧和综艺的夸克转存任务")
public class QuarkIngestController {

    private final QuarkIngestService quarkIngestService;

    public QuarkIngestController(QuarkIngestService quarkIngestService) {
        this.quarkIngestService = quarkIngestService;
    }

    @PostMapping("/movies/tasks")
    @Operation(summary = "创建电影 QAS 任务", description = "持久化 QAS 任务并立即触发一次；不会等待转存完成。")
    public ApiResponse<QuarkIngestTaskResponse> createMovieTask(
            @Valid @RequestBody MovieQuarkIngestRequest request
    ) {
        return ApiResponse.success(quarkIngestService.ingestMovie(request));
    }

    @PostMapping("/movies/preview")
    @Operation(summary = "预览电影 Quark 分享结构和保存计划")
    public ApiResponse<QuarkIngestPreviewResponse> previewMovie(
            @Valid @RequestBody MovieQuarkIngestRequest request
    ) {
        return ApiResponse.success(quarkIngestService.previewMovie(request));
    }

    @PostMapping("/series/tasks")
    @Operation(summary = "创建电视剧 QAS 任务", description = "按电视剧标题和季数生成 /TV 保存路径，持久化后立即触发。")
    public ApiResponse<QuarkIngestTaskResponse> createSeriesTask(
            @Valid @RequestBody SeriesQuarkIngestRequest request
    ) {
        return ApiResponse.success(quarkIngestService.ingestSeries(request));
    }

    @PostMapping("/series/preview")
    @Operation(summary = "预览电视剧 Quark 分享结构和保存计划")
    public ApiResponse<QuarkIngestPreviewResponse> previewSeries(
            @Valid @RequestBody SeriesQuarkIngestRequest request
    ) {
        return ApiResponse.success(quarkIngestService.previewSeries(request));
    }

    @PostMapping("/variety/tasks")
    @Operation(summary = "创建综艺 QAS 任务", description = "按综艺标题和季数生成 /Variety 保存路径，持久化后立即触发。")
    public ApiResponse<QuarkIngestTaskResponse> createVarietyTask(
            @Valid @RequestBody SeriesQuarkIngestRequest request
    ) {
        return ApiResponse.success(quarkIngestService.ingestVariety(request));
    }

    @PostMapping("/variety/preview")
    @Operation(summary = "预览综艺 Quark 分享结构和保存计划")
    public ApiResponse<QuarkIngestPreviewResponse> previewVariety(
            @Valid @RequestBody SeriesQuarkIngestRequest request
    ) {
        return ApiResponse.success(quarkIngestService.previewVariety(request));
    }
}
