package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.auth.request.AuthLoginRequest;
import com.medianexus.orchestrator.dto.auth.request.AuthRegisterRequest;
import com.medianexus.orchestrator.dto.auth.response.AuthSessionResponse;
import com.medianexus.orchestrator.dto.auth.response.AuthUserResponse;
import com.medianexus.orchestrator.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "MediaNexus 用户注册、登录、登出和当前用户接口")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册码注册", description = "使用配置的注册码创建普通用户，注册成功后自动登录并返回访问 token。")
    public ApiResponse<AuthSessionResponse> register(@Valid @RequestBody AuthRegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "使用用户名或邮箱作为账号登录，成功后返回用于 Authorization: Bearer 的 token。")
    public ApiResponse<AuthSessionResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出", description = "清理当前 Bearer token 对应的登录状态。")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success();
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户", description = "根据 Authorization: Bearer token 返回当前登录用户。")
    public ApiResponse<AuthUserResponse> me() {
        return ApiResponse.success(authService.me());
    }
}
