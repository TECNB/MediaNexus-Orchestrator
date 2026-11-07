package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.PreviewInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.*;
import com.medianexus.orchestrator.service.BgmWishlistOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/bgm-wishlist")
@Tag(name = "BGM 异步入库", description = "持久受理、真实搜索进度、自动发布选择和内容例外")
public class AdminBgmWishlistOperationController {
    private final BgmWishlistOperationService operations;
    public AdminBgmWishlistOperationController(BgmWishlistOperationService operations) { this.operations = operations; }
    @PostMapping("/sources/{sourceId}/operations")
    @Operation(summary = "异步入库单部或明确勾选范围；重复进行中的操作返回已有记录")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> submit(
            @PathVariable String sourceId, @Valid @RequestBody PreviewInput input) {
        return ApiResponse.success(operations.submit(sourceId, input.subjectIds()));
    }
    @PostMapping("/sources/{sourceId}/operations/latest")
    @Operation(summary = "异步按最新优先自动选择并入库")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> latest(@PathVariable String sourceId) {
        return ApiResponse.success(operations.latest(sourceId));
    }
    @GetMapping("/sources/{sourceId}/operations")
    @Operation(summary = "分页读取操作记录；关联任务摘要取实时任务状态")
    public ApiResponse<Operations> list(@PathVariable String sourceId, @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(operations.list(sourceId, page, pageSize));
    }
    @GetMapping("/operations/{id}")
    @Operation(summary = "读取持久操作进度，支持离开页面后回看")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> get(@PathVariable String id) {
        return ApiResponse.success(operations.get(id));
    }
    @PostMapping("/operations/{id}/confirm")
    @Operation(summary = "只确认非中文例外的具体候选；后台重新核验后提交")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> confirm(
            @PathVariable String id, @Valid @RequestBody CandidateInput input) {
        return ApiResponse.success(operations.confirm(id, input));
    }
    @PostMapping("/operations/{id}/supply")
    @Operation(summary = "从既有发布选择页补充来源；不允许替换目标或放宽清晰度")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> supply(
            @PathVariable String id, @Valid @RequestBody SupplyInput input) {
        return ApiResponse.success(operations.supply(id, input));
    }
    @PostMapping("/operations/{id}/search")
    @Operation(summary = "任务创建前重新搜索；已创建任务必须在任务中心恢复")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> retry(
            @PathVariable String id, @Valid @RequestBody SubjectInput input) {
        return ApiResponse.success(operations.retry(id, input.subjectId()));
    }
    @PostMapping("/operations/{id}/defer")
    @Operation(summary = "将任务前内容例外标为暂不处理")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> defer(
            @PathVariable String id, @Valid @RequestBody SubjectInput input) {
        return ApiResponse.success(operations.defer(id, input.subjectId()));
    }
}
