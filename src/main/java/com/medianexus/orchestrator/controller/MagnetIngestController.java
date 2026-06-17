package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestResponse;
import com.medianexus.orchestrator.service.MagnetIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/magnet-ingest")
@Tag(name = "电影剧集磁力导入", description = "电影和剧集 magnet 离线下载轻量提交接口")
public class MagnetIngestController {

    private final MagnetIngestService magnetIngestService;

    public MagnetIngestController(MagnetIngestService magnetIngestService) {
        this.magnetIngestService = magnetIngestService;
    }

    @PostMapping("/movies")
    @Operation(summary = "提交电影 magnet 离线下载", description = "按 Core 兼容路径规则创建 OpenList 目录并提交单条 magnet。")
    public ApiResponse<MovieMagnetIngestResponse> ingestMovie(
            @Valid @RequestBody MovieMagnetIngestRequest request
    ) {
        return ApiResponse.success(magnetIngestService.ingestMovie(request));
    }

    @PostMapping("/series")
    @Operation(summary = "提交剧集 magnet 离线下载", description = "按 Core 兼容路径规则创建 Season 目录并提交单条 magnet。")
    public ApiResponse<SeriesMagnetIngestResponse> ingestSeries(
            @Valid @RequestBody SeriesMagnetIngestRequest request
    ) {
        return ApiResponse.success(magnetIngestService.ingestSeries(request));
    }
}
