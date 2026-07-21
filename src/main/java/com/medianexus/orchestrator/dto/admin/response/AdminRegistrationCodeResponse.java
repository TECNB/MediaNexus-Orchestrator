package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员注册码响应")
public record AdminRegistrationCodeResponse(
        @Schema(description = "当前有效注册码；未开放注册时为空")
        @JsonProperty("registration_code")
        String registrationCode,

        @Schema(description = "注册码来源：DATABASE、CONFIG 或 NONE")
        String source,

        @Schema(description = "当前注册码绑定的邀请人用户 id；未绑定时为空", nullable = true)
        @JsonProperty("inviter_user_id")
        Long inviterUserId,

        @Schema(description = "当前注册码绑定的邀请人用户名快照；未绑定时为空", nullable = true)
        @JsonProperty("inviter_username")
        String inviterUsername
) {
}
