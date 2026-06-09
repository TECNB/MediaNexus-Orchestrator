package com.medianexus.orchestrator.dto.auth.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "当前用户信息")
public record AuthUserResponse(
        @Schema(description = "用户 id")
        Long id,
        @Schema(description = "用户名，已按小写归一化")
        String username,
        @Schema(description = "邮箱，已按小写归一化")
        String email,
        @Schema(description = "用户角色：ADMIN 或 USER")
        String role,
        @Schema(description = "账号创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt
) {
}

