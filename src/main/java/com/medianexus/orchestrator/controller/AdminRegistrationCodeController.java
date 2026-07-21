package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.admin.request.AdminRegistrationCodeGenerateRequest;
import com.medianexus.orchestrator.dto.admin.response.AdminRegistrationCodeResponse;
import com.medianexus.orchestrator.service.AdminRegistrationCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/registration-code")
@Tag(name = "注册码管理", description = "管理员查看和生成当前有效注册码")
public class AdminRegistrationCodeController {

    private final AdminRegistrationCodeService registrationCodeService;

    public AdminRegistrationCodeController(AdminRegistrationCodeService registrationCodeService) {
        this.registrationCodeService = registrationCodeService;
    }

    @GetMapping
    @Operation(summary = "读取当前有效注册码", description = "返回数据库注册码；若未配置数据库注册码，则回退到环境配置注册码。")
    public ApiResponse<AdminRegistrationCodeResponse> getCurrentRegistrationCode() {
        return ApiResponse.success(registrationCodeService.getCurrentRegistrationCode());
    }

    @PostMapping("/generate")
    @Operation(summary = "生成注册码", description = "生成新的注册码并保存到数据库，后续注册立即使用新注册码校验。")
    public ApiResponse<AdminRegistrationCodeResponse> generateRegistrationCode(
            @RequestBody(required = false) AdminRegistrationCodeGenerateRequest request
    ) {
        return ApiResponse.success(registrationCodeService.generateRegistrationCode(request));
    }
}
