package com.medianexus.orchestrator.integration.emby;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Emby 当前视频播放会话；同账号不同设备分别返回，保留暂停")
public record EmbyPlaybackSession(
        String id,
        @Schema(description = "Emby 用户 ID；未返回时为 null", nullable = true) String userId,
        @Schema(description = "播放用户；未返回时为 null", nullable = true) String userName,
        @Schema(description = "客户端；未返回时为 null", nullable = true) String client,
        @Schema(description = "设备；未返回时为 null", nullable = true) String deviceName,
        @Schema(description = "暂停状态；上游未返回时为 null", nullable = true) Boolean paused,
        String itemId, String type,
        @Schema(description = "当前内容标题；未返回时为 null", nullable = true) String title,
        @Schema(description = "剧名；未返回时为 null", nullable = true) String seriesName,
        @Schema(description = "季号；0 为特典，未返回时为 null", nullable = true) Integer seasonNumber,
        @Schema(description = "集号；未返回时为 null", nullable = true) Integer episodeNumber,
        @Schema(description = "多集结束号；未返回时为 null", nullable = true) Integer episodeNumberEnd,
        @Schema(description = "当前会话播放位置，单位秒；未返回时为 null", nullable = true) Double positionSeconds,
        @Schema(description = "当前媒体版本总秒数；无法匹配版本或未返回时为 null", nullable = true) Double durationSeconds,
        @Schema(description = "封面代理候选，依次为剧、季、单集；不是媒体源 URL") List<Poster> posters
) {
    @Schema(description = "通过既有管理员封面代理读取")
    public record Poster(String itemId, @Schema(description = "图片版本；未知时为 null", nullable = true) String imageTag) {}
}
