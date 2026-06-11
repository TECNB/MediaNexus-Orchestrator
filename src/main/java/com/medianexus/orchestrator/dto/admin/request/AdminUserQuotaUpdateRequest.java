package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "管理员更新用户额度覆盖请求")
public record AdminUserQuotaUpdateRequest(
        @Schema(description = "用户每日创建额度覆盖；null 表示恢复全局默认", nullable = true)
        @Min(value = 0, message = "额度必须为 0-9 的整数")
        @Max(value = 9, message = "额度必须为 0-9 的整数")
        @JsonProperty("daily_content_create_limit_override")
        @JsonAlias("dailyContentCreateLimitOverride")
        Integer dailyContentCreateLimitOverride
) {
}
