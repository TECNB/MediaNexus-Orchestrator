package com.medianexus.orchestrator.dto.auth.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "登录请求")
public record AuthLoginRequest(
        @Schema(description = "用户名或邮箱")
        @NotBlank(message = "账号不能为空")
        String account,
        @Schema(description = "密码")
        @NotBlank(message = "密码不能为空")
        String password
) {
}
