package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 管理员所选统计范围内的观看排行和完整明细。 */
public final class EmbyWatchDetails {
    private EmbyWatchDetails() {}

    @Schema(description = "观看记录分页；total 为完整结果数量")
    public record Page<T>(List<T> items, int page, @JsonProperty("page_size") int pageSize, long total) {}

    @Schema(description = "已结算用户观看排行；不包含进行中估算")
    public record User(
            int rank,
            @JsonProperty("emby_user_id") String embyUserId,
            @JsonProperty("user_name") String userName,
            @JsonProperty("watch_seconds") long watchSeconds,
            @JsonProperty("play_count") long playCount,
            @JsonProperty("work_count") long workCount,
            @Schema(description = "最近播放的结束时间；记录缺失时为 null", nullable = true)
            @JsonProperty("last_watched_at") LocalDateTime lastWatchedAt,
            @JsonProperty("last_item_name") String lastItemName,
            @JsonProperty("last_item_id") String lastItemId,
            @JsonProperty("last_media_id") String lastMediaId,
            @JsonProperty("last_category") String lastCategory,
            @Schema(description = "最近播放季号；未知时为 null", nullable = true)
            @JsonProperty("last_season_number") Integer lastSeasonNumber,
            @Schema(description = "最近播放集号；未知时为 null", nullable = true)
            @JsonProperty("last_episode_number") Integer lastEpisodeNumber
    ) {}

    @Schema(description = "整部作品的已结算统计；敏感或未知分类默认隐藏片名且无海报")
    public record Work(
            int rank,
            @JsonProperty("media_id") String mediaId,
            String title,
            @Schema(description = "分类：movie、series、adult 或 unknown") String category,
            @Schema(description = "综艺等内容标签；无标签时为 null", nullable = true)
            @JsonProperty("content_label") String contentLabel,
            @Schema(description = "可靠的电影或整剧海报条目 ID；敏感、未知或缺身份时为 null", nullable = true)
            @JsonProperty("poster_item_id") String posterItemId,
            @JsonProperty("watch_seconds") long watchSeconds,
            @JsonProperty("play_count") long playCount,
            @JsonProperty("viewer_count") long viewerCount,
            @JsonProperty("last_played_at") LocalDateTime lastPlayedAt,
            @Schema(description = "按最近播放确定的季号；未知时为 null", nullable = true)
            @JsonProperty("season_number") Integer seasonNumber,
            @Schema(description = "按最近播放确定的集号；未知时为 null", nullable = true)
            @JsonProperty("episode_number") Integer episodeNumber
    ) {}

    @Schema(description = "一次播放的跨度、整次时长和所选范围贡献；估算不进入正式排行")
    public record Session(
            String id,
            @JsonProperty("media_id") String mediaId,
            @JsonProperty("item_id") String itemId,
            String title,
            String category,
            @Schema(description = "季号；未知时为 null", nullable = true)
            @JsonProperty("season_number") Integer seasonNumber,
            @Schema(description = "集号；未知时为 null", nullable = true)
            @JsonProperty("episode_number") Integer episodeNumber,
            @JsonProperty("start_time") LocalDateTime startTime,
            @Schema(description = "会话结束时间；进行中或未核实时为 null", nullable = true)
            @JsonProperty("stop_time") LocalDateTime stopTime,
            @Schema(description = "整次播放观看秒数；进行中为截至 observed_at 的估算")
            @JsonProperty("watch_seconds") long watchSeconds,
            @Schema(description = "该会话在所选日或月的观看秒数")
            @JsonProperty("range_watch_seconds") long rangeWatchSeconds,
            @Schema(description = "calendar 为自然日，legacy 为结束日历史口径")
            @JsonProperty("accounting_basis") String accountingBasis,
            @Schema(description = "停止时间来源：event 事件、superseded 后续播放推定、unknown 历史未知；未结束时为 null", nullable = true)
            @JsonProperty("stop_time_source") String stopTimeSource,
            @Schema(description = "设备；未知时为 null", nullable = true)
            @JsonProperty("device_name") String deviceName,
            @Schema(description = "客户端；未知时为 null", nullable = true)
            @JsonProperty("client_name") String clientName,
            @JsonProperty("emby_user_id") String embyUserId,
            @JsonProperty("user_name") String userName,
            @Schema(description = "settled 为已结算；playing、paused、unverified 为播放、暂停或待确认估算")
            String state,
            @Schema(description = "估算核验或冻结时间；已结算时为 null", nullable = true)
            @JsonProperty("observed_at") LocalDateTime observedAt
    ) {}

    @Schema(description = "某作品在所选范围内的观看用户及其已结算贡献")
    public record Viewer(
            @JsonProperty("emby_user_id") String embyUserId,
            @JsonProperty("user_name") String userName,
            @JsonProperty("watch_seconds") long watchSeconds,
            @JsonProperty("play_count") long playCount
    ) {}
}
