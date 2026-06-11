package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.admin.request.AdminDefaultQuotaUpdateRequest;
import com.medianexus.orchestrator.dto.admin.request.AdminUserQuotaUpdateRequest;
import com.medianexus.orchestrator.dto.admin.response.AdminDefaultQuotaResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserListResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserQuotaResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserSummaryResponse;
import com.medianexus.orchestrator.service.AdminUserManagementService;
import com.medianexus.orchestrator.service.UserQuotaSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "用户管理", description = "管理员查看用户、调整额度和重置今日使用次数接口")
public class AdminUserController {

    private final AdminUserManagementService adminUserManagementService;
    private final UserQuotaSettingsService quotaSettingsService;

    public AdminUserController(
            AdminUserManagementService adminUserManagementService,
            UserQuotaSettingsService quotaSettingsService
    ) {
        this.adminUserManagementService = adminUserManagementService;
        this.quotaSettingsService = quotaSettingsService;
    }

    @GetMapping("/users")
    @Operation(summary = "分页列出用户", description = "管理员分页查看用户、今日共享额度用量、额度来源和操作状态。")
    public ApiResponse<AdminUserListResponse> listUsers(
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(name = "page", required = false) Integer page,
            @Parameter(description = "每页条数，最大 100")
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @Parameter(description = "用户名或邮箱关键词")
            @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "角色筛选：ALL、USER 或 ADMIN")
            @RequestParam(name = "role", required = false) String role,
            @Parameter(description = "排序：CREATED_AT_DESC、CREATED_AT_ASC、USED_COUNT_DESC 或 USED_COUNT_ASC")
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return ApiResponse.success(adminUserManagementService.listUsers(page, pageSize, keyword, role, sort));
    }

    @GetMapping("/users/summary")
    @Operation(summary = "用户管理总览", description = "返回用户总数、普通用户数和普通用户今日最高用量。")
    public ApiResponse<AdminUserSummaryResponse> getSummary() {
        return ApiResponse.success(adminUserManagementService.getSummary());
    }

    @GetMapping("/quota/default")
    @Operation(summary = "读取全局默认额度", description = "返回普通用户未配置覆盖额度时继承的每日共享创建额度。")
    public ApiResponse<AdminDefaultQuotaResponse> getDefaultQuota() {
        return ApiResponse.success(quotaSettingsService.getDefaultQuotaForAdmin());
    }

    @PutMapping("/quota/default")
    @Operation(summary = "更新全局默认额度", description = "管理员把全局默认每日共享创建额度更新为 0-9 的整数。")
    public ApiResponse<AdminDefaultQuotaResponse> updateDefaultQuota(
            @Valid @RequestBody AdminDefaultQuotaUpdateRequest request
    ) {
        return ApiResponse.success(quotaSettingsService.updateDefaultQuota(
                request == null ? null : request.dailyContentCreateLimit()
        ));
    }

    @PutMapping("/users/{userId}/quota")
    @Operation(summary = "更新用户覆盖额度", description = "管理员为普通用户设置 0-9 的覆盖额度，或传 null 恢复全局默认。")
    public ApiResponse<AdminUserQuotaResponse> updateUserQuota(
            @Parameter(description = "目标用户 id")
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserQuotaUpdateRequest request
    ) {
        return ApiResponse.success(adminUserManagementService.updateUserQuota(userId, request));
    }

    @PostMapping("/users/{userId}/usage/reset-today")
    @Operation(summary = "重置今日次数", description = "把普通用户今日两个共享额度动作的已用次数重置为 0。")
    public ApiResponse<AdminUserResponse> resetTodayUsage(
            @Parameter(description = "目标用户 id")
            @PathVariable Long userId
    ) {
        return ApiResponse.success(adminUserManagementService.resetTodayUsage(userId));
    }
}
