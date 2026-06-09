package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员用户额度更新响应")
public record AdminUserQuotaResponse(
        @Schema(description = "用户 id")
        @JsonProperty("user_id")
        Long userId,
        @Schema(description = "用户额度覆盖；null 表示跟随全局默认", nullable = true)
        @JsonProperty("daily_content_create_limit_override")
        Integer dailyContentCreateLimitOverride,
        @Schema(description = "当前有效额度")
        @JsonProperty("effective_daily_content_create_limit")
        int effectiveDailyContentCreateLimit,
        @Schema(description = "额度来源：GLOBAL_DEFAULT 或 USER_OVERRIDE")
        @JsonProperty("quota_source")
        String quotaSource
) {
}
