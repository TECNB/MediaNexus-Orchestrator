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
        LocalDateTime lastWatchedAt,
        @Schema(description = "按实际自然日贡献的已结算会话数")
        @JsonProperty("calendar_session_count") long calendarSessionCount,
        @Schema(description = "按结束日入账的历史会话数")
        @JsonProperty("legacy_session_count") long legacySessionCount,
        @Schema(description = "整个统计范围内的作品数，含私密及未知分类")
        @JsonProperty("total_work_count") long totalWorkCount
) {
}
