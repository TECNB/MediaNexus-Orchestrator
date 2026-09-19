package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JAVDB 自动化配置与凭证状态")
public record JavdbAutomationConfigResponse(
        boolean enabled,
        @JsonProperty("daily_enabled") boolean dailyEnabled,
        @JsonProperty("weekly_enabled") boolean weeklyEnabled,
        @JsonProperty("monthly_enabled") boolean monthlyEnabled,
        @JsonProperty("cracked_only") boolean crackedOnly,
        @JsonProperty("subtitle_only") boolean subtitleOnly,
        @JsonProperty("excluded_tags") String excludedTags,
        @JsonProperty("minimum_rating") double minimumRating,
        @JsonProperty("minimum_review_count") int minimumReviewCount,
        @JsonProperty("limit_per_ranking") int limitPerRanking,
        @JsonProperty("schedule_time") String scheduleTime,
        String timezone,
        @JsonProperty("credential_configured") boolean credentialConfigured,
        @JsonProperty("credential_valid") boolean credentialValid,
        @JsonProperty("last_validated_at") String lastValidatedAt,
        @JsonProperty("top_credential_configured") boolean topCredentialConfigured,
        @JsonProperty("top_credential_valid") boolean topCredentialValid,
        @JsonProperty("top_last_validated_at") String topLastValidatedAt
) {
}
