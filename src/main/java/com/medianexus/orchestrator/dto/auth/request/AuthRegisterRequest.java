package com.medianexus.orchestrator.dto.auth.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "注册码注册请求")
public record AuthRegisterRequest(
        @Schema(description = "用户名，3-32 位，只允许小写字母、数字、下划线和短横线")
        @NotBlank(message = "用户名不能为空")
        String username,
        @Schema(description = "邮箱")
        @NotBlank(message = "邮箱不能为空")
        String email,
        @Schema(description = "密码，8-32 位")
        @NotBlank(message = "密码不能为空")
        String password,
        @Schema(description = "确认密码")
        @NotBlank(message = "确认密码不能为空")
        @JsonProperty("confirm_password")
        @JsonAlias("confirmPassword")
        String confirmPassword,
        @Schema(description = "注册码")
        @NotBlank(message = "注册码不能为空")
        @JsonProperty("registration_code")
        @JsonAlias("registrationCode")
        String registrationCode
) {
}
