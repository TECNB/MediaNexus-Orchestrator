package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.autosymlink.request.AutoSymlinkRefreshRequest;
import com.medianexus.orchestrator.dto.autosymlink.response.AutoSymlinkRefreshResponse;
import com.medianexus.orchestrator.service.AdminAutoSymlinkRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/autosymlink")
@Tag(name = "AutoSymlink 管理", description = "管理员手动提交 AutoSymlink 同步任务")
public class AdminAutoSymlinkController {

    private final AdminAutoSymlinkRefreshService refreshService;

    public AdminAutoSymlinkController(AdminAutoSymlinkRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @PostMapping("/refresh")
    @Operation(summary = "手动提交 AutoSymlink 同步任务")
    public ApiResponse<AutoSymlinkRefreshResponse> refresh(
            @Valid @RequestBody AutoSymlinkRefreshRequest request
    ) {
        return ApiResponse.success(refreshService.refresh(request));
    }
}
