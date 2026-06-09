package com.medianexus.orchestrator.dto.auth.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "注册码注册请求")
public record AuthRegisterRequest(
        @Schema(description = "用户名，3-32 位，只允许小写字母、数字、下划线和短横线")
        String username,
        @Schema(description = "邮箱")
        String email,
        @Schema(description = "密码，8-32 位")
        String password,
        @Schema(description = "确认密码")
        @JsonProperty("confirm_password")
        @JsonAlias("confirmPassword")
        String confirmPassword,
        @Schema(description = "注册码")
        @JsonProperty("registration_code")
        @JsonAlias("registrationCode")
        String registrationCode
) {
}

