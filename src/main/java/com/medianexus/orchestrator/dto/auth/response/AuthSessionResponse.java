package com.medianexus.orchestrator.dto.auth.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "登录会话响应")
public record AuthSessionResponse(
        @Schema(description = "用于 Authorization: Bearer 的访问 token")
        String token,
        @Schema(description = "当前用户信息")
        AuthUserResponse user
) {
}

