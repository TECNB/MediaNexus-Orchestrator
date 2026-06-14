package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Emby 观看活跃与排行统计")
public record EmbyWatchRankingResponse(
        @Schema(description = "统计日期，北京时间自然日")
        LocalDate date,
        @Schema(description = "统计时区")
        String timezone,
        @Schema(description = "生成时间")
        @JsonProperty("generated_at")
        LocalDateTime generatedAt,
        @Schema(description = "观看概览")
        EmbyWatchRankingSummaryResponse summary,
        @Schema(description = "Emby 用户观看排行")
        List<EmbyUserWatchRankingItem> users,
        @Schema(description = "电影观看排行")
        List<EmbyMediaWatchRankingItem> movies,
        @Schema(description = "电视剧/番剧观看排行")
        List<EmbyMediaWatchRankingItem> series,
        @Schema(description = "Webhook 接入状态")
        @JsonProperty("webhook_status")
        EmbyWebhookStatusResponse webhookStatus
) {
}
