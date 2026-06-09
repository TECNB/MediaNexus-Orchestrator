package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "管理员用户列表条目")
public record AdminUserResponse(
        @Schema(description = "用户 id")
        Long id,
        @Schema(description = "用户名")
        String username,
        @Schema(description = "邮箱")
        String email,
        @Schema(description = "用户角色：ADMIN 或 USER")
        String role,
        @Schema(description = "用户额度覆盖；null 表示跟随全局默认", nullable = true)
        @JsonProperty("daily_content_create_limit_override")
        Integer dailyContentCreateLimitOverride,
        @Schema(description = "有效每日创建额度；管理员无限制时为 null", nullable = true)
        @JsonProperty("effective_daily_content_create_limit")
        Integer effectiveDailyContentCreateLimit,
        @Schema(description = "额度来源：GLOBAL_DEFAULT、USER_OVERRIDE 或 SYSTEM_UNLIMITED")
        @JsonProperty("quota_source")
        String quotaSource,
        @Schema(description = "今日已用次数合计")
        @JsonProperty("today_used_count")
        int todayUsedCount,
        @Schema(description = "用量状态：AVAILABLE、REACHED_LIMIT、EXCEEDED 或 UNLIMITED")
        @JsonProperty("usage_status")
        String usageStatus,
        @Schema(description = "今日用量动作拆分")
        @JsonProperty("usage_breakdown")
        AdminUserUsageBreakdownResponse usageBreakdown,
        @Schema(description = "账号创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt,
        @Schema(description = "账号最后更新时间")
        @JsonProperty("updated_at")
        LocalDateTime updatedAt
) {
}
