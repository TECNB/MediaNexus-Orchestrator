package com.medianexus.orchestrator.dto.javdb.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "JAVDB 自动化配置更新请求")
public record JavdbAutomationConfigUpdateRequest(
        @Schema(description = "是否启用每日自动同步")
        @NotNull(message = "启用状态不能为空")
        Boolean enabled,
        @Schema(description = "是否读取有码日榜")
        @JsonProperty("daily_enabled")
        @NotNull(message = "日榜开关不能为空")
        Boolean dailyEnabled,
        @Schema(description = "是否读取有码周榜")
        @JsonProperty("weekly_enabled")
        @NotNull(message = "周榜开关不能为空")
        Boolean weeklyEnabled,
        @Schema(description = "是否读取有码月榜")
        @JsonProperty("monthly_enabled")
        @NotNull(message = "月榜开关不能为空")
        Boolean monthlyEnabled,
        @Schema(description = "每个榜单读取前 N 条，范围 1-50")
        @JsonProperty("limit_per_ranking")
        @NotNull(message = "榜单数量不能为空")
        @Min(value = 1, message = "每个榜单至少读取 1 条")
        @Max(value = 50, message = "每个榜单最多读取 50 条")
        Integer limitPerRanking,
        @Schema(description = "每日执行时间，格式 HH:mm")
        @JsonProperty("schedule_time")
        @NotNull(message = "执行时间不能为空")
        @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d", message = "执行时间格式必须为 HH:mm")
        String scheduleTime
) {
}
