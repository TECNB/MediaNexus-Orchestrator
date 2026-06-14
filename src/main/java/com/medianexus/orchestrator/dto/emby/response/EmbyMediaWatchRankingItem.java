package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Emby 作品观看排行条目")
public record EmbyMediaWatchRankingItem(
        @Schema(description = "当前排行名次")
        int rank,
        @Schema(description = "媒体聚合 id；电影为 itemId，剧集为 seriesId、seriesName 或 itemId")
        @JsonProperty("media_id")
        String mediaId,
        @Schema(description = "作品标题")
        String title,
        @Schema(description = "总观看秒数")
        @JsonProperty("watch_seconds")
        long watchSeconds,
        @Schema(description = "有效播放次数")
        @JsonProperty("play_count")
        long playCount,
        @Schema(description = "最近一次播放结束时间")
        @JsonProperty("last_played_at")
        LocalDateTime lastPlayedAt
) {
}
