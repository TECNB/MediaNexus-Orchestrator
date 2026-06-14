package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Emby 用户观看排行条目")
public record EmbyUserWatchRankingItem(
        @Schema(description = "当前排行名次")
        int rank,
        @Schema(description = "Emby 用户 id")
        @JsonProperty("emby_user_id")
        String embyUserId,
        @Schema(description = "Emby 用户名快照")
        @JsonProperty("user_name")
        String userName,
        @Schema(description = "总观看秒数")
        @JsonProperty("watch_seconds")
        long watchSeconds,
        @Schema(description = "有效播放次数")
        @JsonProperty("play_count")
        long playCount,
        @Schema(description = "最近一次有效观看结束时间")
        @JsonProperty("last_watched_at")
        LocalDateTime lastWatchedAt,
        @Schema(description = "最近观看内容标题；上游字段缺失时为 null", nullable = true)
        @JsonProperty("last_item_name")
        String lastItemName
) {
}
