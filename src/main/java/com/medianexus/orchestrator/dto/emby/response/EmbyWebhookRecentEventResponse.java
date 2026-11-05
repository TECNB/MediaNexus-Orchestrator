package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "最近播放动向；来源区分采样观察和旧 Webhook")
public record EmbyWebhookRecentEventResponse(
        @Schema(description = "事件名称，例如 playback.start 或 playback.stop")
        String event,
        @Schema(description = "事件时间")
        @JsonProperty("event_time")
        LocalDateTime eventTime,
        @Schema(description = "Emby 用户名快照")
        @JsonProperty("user_name")
        String userName,
        @Schema(description = "内容标题")
        @JsonProperty("item_name")
        String itemName,
        @Schema(description = "已结算观看秒数；start 事件为 null", nullable = true)
        @JsonProperty("watch_seconds")
        Integer watchSeconds,
        @JsonProperty("item_id") String itemId,
        @JsonProperty("media_id") String mediaId,
        @Schema(description = "作品分类；未知分类不提供片名或海报") String category,
        @Schema(description = "采集来源：sampling 采样，webhook 旧事件") String source,
        @Schema(description = "结束来源：inferred 采样推定、migration 旧活动交接，或旧 event/superseded/unknown；开始为 null", nullable = true)
        @JsonProperty("stop_time_source") String stopTimeSource
) {
}
