package com.medianexus.orchestrator.controller.accounts;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.service.settings.targets.DownloadAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/download-accounts")
@Tag(name = "下载账号", description = "真实资源接管、接入计划与分类默认；不回显认证信息")
public class DownloadAccountController {
    private final DownloadAccountService accounts;
    public DownloadAccountController(DownloadAccountService accounts) { this.accounts = accounts; }
    @GetMapping @Operation(summary = "读取账号和类别默认")
    public ApiResponse<AccountList> list() { return ApiResponse.success(accounts.list()); }
    @GetMapping("/{targetId}") @Operation(summary = "读取账号详情")
    public ApiResponse<AccountDetail> detail(@PathVariable String targetId) { return ApiResponse.success(accounts.detail(targetId)); }
    @PostMapping("/{targetId}/check") @Operation(summary = "只读检查已登记资源")
    public ApiResponse<AccountDetail> check(@PathVariable String targetId) { return ApiResponse.success(accounts.check(targetId)); }
    @PostMapping("/adoption-plans") @Operation(summary = "生成已有主号接管预览")
    public ApiResponse<AdoptionPlan> adopt(@Valid @RequestBody AdoptionRequest request) { return ApiResponse.success(accounts.previewAdoption(request)); }
    @PostMapping("/adoption-plans/{planId}/confirm") @Operation(summary = "确认已有主号资源接管")
    public ApiResponse<AccountDetail> confirm(@PathVariable String planId, @Valid @RequestBody AdoptionConfirmRequest request) {
        return ApiResponse.success(accounts.confirmAdoption(planId, request));
    }
    @PostMapping("/onboarding-plans") @Operation(summary = "生成持久化新号接入计划")
    public ApiResponse<OnboardingPlan> plan(@Valid @RequestBody OnboardingPlanRequest request) { return ApiResponse.success(accounts.previewOnboarding(request)); }
    @GetMapping("/onboarding-plans/{planId}") @Operation(summary = "读取接入计划")
    public ApiResponse<OnboardingPlan> plan(@PathVariable String planId) { return ApiResponse.success(accounts.plan(planId)); }
    @PostMapping("/onboarding-plans/{planId}/execute") @Operation(summary = "显式提交接入执行")
    public ApiResponse<Execution> execute(@PathVariable String planId, @Valid @RequestBody ExecuteRequest request) {
        return ApiResponse.success(accounts.execute(planId, request));
    }
    @GetMapping("/onboarding-executions") @Operation(summary = "读取持久执行历史")
    public ApiResponse<ExecutionList> executions(@RequestParam(required = false) String planId) { return ApiResponse.success(accounts.executions(planId)); }
    @GetMapping("/onboarding-executions/{executionId}") @Operation(summary = "读取执行进度")
    public ApiResponse<Execution> execution(@PathVariable String executionId) { return ApiResponse.success(accounts.execution(executionId)); }
    @PostMapping("/onboarding-executions/{executionId}/continue") @Operation(summary = "核对前提后继续中断执行")
    public ApiResponse<Execution> resume(@PathVariable String executionId, @Valid @RequestBody ContinueRequest request) {
        return ApiResponse.success(accounts.resume(executionId, request));
    }
    @PutMapping("/category-defaults") @Operation(summary = "按版本原子切换所选类别默认")
    public ApiResponse<AccountList> defaults(@Valid @RequestBody UpdateDefaultsRequest request) { return ApiResponse.success(accounts.switchDefaults(request)); }
    @PutMapping("/{targetId}/accepting-new-tasks") @Operation(summary = "停止或恢复账号接收新任务")
    public ApiResponse<AccountDetail> accepting(@PathVariable String targetId, @Valid @RequestBody UpdateAcceptingRequest request) {
        return ApiResponse.success(accounts.setAccepting(targetId, request));
    }
}
