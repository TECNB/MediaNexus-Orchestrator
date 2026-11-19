package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseSearchResponse;
import com.medianexus.orchestrator.service.PanSouMagnetSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resources/releases/comparisons")
@Tag(name = "文件表候选比较", description = "当前登录用户的搜索快照；轮询不自动延长预算")
public class ReleaseComparisonController {
    private final PanSouMagnetSearch comparisons;
    public ReleaseComparisonController(PanSouMagnetSearch comparisons) { this.comparisons = comparisons; }

    @GetMapping("/{id}")
    @Operation(summary = "读取当前比较快照", description = "只读进度与已查证据，过期后须重新搜索。")
    public ApiResponse<ProwlarrReleaseSearchResponse> get(@PathVariable String id) {
        return ApiResponse.success(comparisons.comparison(id));
    }

    @PostMapping("/{id}/continue")
    @Operation(summary = "明确继续下一轮比较", description = "复用已查文件表及队列，使用新一轮有界预算；不会下载或自动入库。")
    public ApiResponse<ProwlarrReleaseSearchResponse> resume(@PathVariable String id) {
        return ApiResponse.success(comparisons.continueComparison(id));
    }
}
