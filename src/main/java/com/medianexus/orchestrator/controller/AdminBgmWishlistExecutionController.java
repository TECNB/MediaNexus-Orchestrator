package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.*;
import com.medianexus.orchestrator.service.BgmWishlistExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/bgm-wishlist")
@Tag(name = "BGM 想看执行", description = "管理员预览、确认和持久执行历史")
public class AdminBgmWishlistExecutionController {
    private final BgmWishlistExecutionService execution;

    public AdminBgmWishlistExecutionController(BgmWishlistExecutionService execution) {
        this.execution = execution;
    }

    @PostMapping("/sources/{sourceId}/preview")
    @Operation(summary = "预览单部或所选批量；不预留额度")
    public ApiResponse<Preview> preview(@PathVariable String sourceId, @Valid @RequestBody PreviewInput input) {
        return ApiResponse.success(execution.preview(sourceId, input));
    }

    @PostMapping("/sources/{sourceId}/preview-latest")
    @Operation(summary = "按最新优先预览配置目标数")
    public ApiResponse<Preview> latest(@PathVariable String sourceId) {
        return ApiResponse.success(execution.previewLatest(sourceId));
    }

    @PostMapping("/sources/{sourceId}/execute")
    @Operation(summary = "一次确认预览范围；重新检查只能减少作品")
    public ApiResponse<Run> execute(@PathVariable String sourceId, @Valid @RequestBody ExecuteInput input) {
        return ApiResponse.success(execution.execute(sourceId, input));
    }

    @GetMapping("/runs")
    @Operation(summary = "按新到旧分页查看手动与每日执行")
    public ApiResponse<Runs> runs(@RequestParam(required = false) String sourceId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(execution.runs(sourceId, page, pageSize));
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "读取执行快照、真实额度与逐项任务关联")
    public ApiResponse<Run> run(@PathVariable String runId) {
        return ApiResponse.success(execution.run(runId));
    }
}
