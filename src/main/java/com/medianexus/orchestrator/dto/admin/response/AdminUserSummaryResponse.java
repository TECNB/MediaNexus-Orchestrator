package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理员用户管理总览响应")
public record AdminUserSummaryResponse(
        @Schema(description = "全部用户数，包含管理员")
        @JsonProperty("total_users")
        long totalUsers,
        @Schema(description = "普通用户数，不包含管理员")
        @JsonProperty("normal_users")
        long normalUsers,
        @Schema(description = "今日普通用户最高用量")
        @JsonProperty("highest_usage_count")
        int highestUsageCount,
        @Schema(description = "达到今日最高用量的普通用户数；最高用量为 0 时返回 0")
        @JsonProperty("highest_usage_user_count")
        long highestUsageUserCount
) {
}
