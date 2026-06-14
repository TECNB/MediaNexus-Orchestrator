package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Emby 指定日期观看概览")
public record EmbyWatchRankingSummaryResponse(
        @Schema(description = "活跃 Emby 用户数量")
        @JsonProperty("active_user_count")
        long activeUserCount,
        @Schema(description = "总观看秒数")
        @JsonProperty("total_watch_seconds")
        long totalWatchSeconds,
        @Schema(description = "有效播放次数")
        @JsonProperty("total_play_count")
        long totalPlayCount,
        @Schema(description = "最近一次有效观看结束时间；当天无数据时为 null", nullable = true)
        @JsonProperty("last_watched_at")
        LocalDateTime lastWatchedAt
) {
}
