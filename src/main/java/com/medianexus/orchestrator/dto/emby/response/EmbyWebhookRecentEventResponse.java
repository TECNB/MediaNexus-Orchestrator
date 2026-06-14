package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Emby Webhook 最近事件")
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
        Integer watchSeconds
) {
}
