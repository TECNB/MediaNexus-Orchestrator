package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JAVDB 自动化配置与凭证状态")
public record JavdbAutomationConfigResponse(
        boolean enabled,
        @JsonProperty("daily_enabled") boolean dailyEnabled,
        @JsonProperty("weekly_enabled") boolean weeklyEnabled,
        @JsonProperty("monthly_enabled") boolean monthlyEnabled,
        @JsonProperty("limit_per_ranking") int limitPerRanking,
        @JsonProperty("schedule_time") String scheduleTime,
        String timezone,
        @JsonProperty("credential_configured") boolean credentialConfigured,
        @JsonProperty("credential_valid") boolean credentialValid,
        @JsonProperty("last_validated_at") String lastValidatedAt
) {
}
