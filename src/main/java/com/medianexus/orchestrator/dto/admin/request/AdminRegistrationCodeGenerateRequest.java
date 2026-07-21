package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "生成注册码请求")
public record AdminRegistrationCodeGenerateRequest(
        @Schema(description = "邀请人用户 id；为空表示管理员直接邀请", nullable = true)
        @JsonProperty("inviter_user_id")
        Long inviterUserId
) {
}
