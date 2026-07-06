package com.medianexus.orchestrator.dto.emby.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Emby Webhooks 播放事件请求")
public record EmbyPlaybackWebhookRequest(
        @Schema(description = "Emby 事件名称，例如 playback.start 或 playback.stop")
        String event,
        @Schema(description = "事件时间，建议使用 UTC ISO-8601 字符串")
        String date,
        @Schema(description = "Emby 用户 id")
        String userId,
        @Schema(description = "Emby 用户名")
        String userName,
        @Schema(description = "Emby 播放会话 id")
        String sessionId,
        @Schema(description = "Emby 媒体条目 id")
        String itemId,
        @Schema(description = "媒体条目类型，一期仅处理 Movie 或 Episode")
        String itemType,
        @Schema(description = "媒体条目标题")
        String itemName,
        @Schema(description = "剧集系列 id；Movie 可为空", nullable = true)
        String seriesId,
        @Schema(description = "剧集系列名称；Movie 可为空", nullable = true)
        String seriesName,
        @Schema(description = "剧集季号；Movie 可为空", nullable = true)
        Integer seasonNumber,
        @Schema(description = "剧集集号；Movie 可为空", nullable = true)
        Integer episodeNumber,
        @Schema(description = "媒体总时长 ticks；10,000,000 ticks = 1 秒", nullable = true)
        String runtimeTicks,
        @Schema(description = "当前播放位置 ticks；10,000,000 ticks = 1 秒")
        String positionTicks,
        @Schema(description = "播放设备名称；上游未返回时为 null", nullable = true)
        String deviceName,
        @Schema(description = "播放客户端名称；上游未返回时为 null", nullable = true)
        String clientName
) {
}
