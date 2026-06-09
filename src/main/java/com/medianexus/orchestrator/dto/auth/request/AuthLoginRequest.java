package com.medianexus.orchestrator.dto.auth.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "登录请求")
public record AuthLoginRequest(
        @Schema(description = "用户名或邮箱")
        String account,
        @Schema(description = "密码")
        String password
) {
}

