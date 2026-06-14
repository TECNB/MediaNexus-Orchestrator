package com.medianexus.orchestrator.dto.emby.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Emby Webhook 接入状态")
public record EmbyWebhookStatusResponse(
        @Schema(description = "Webhook secret 是否已配置")
        @JsonProperty("secret_configured")
        boolean secretConfigured,
        @Schema(description = "当前未结束播放会话数量")
        @JsonProperty("active_session_count")
        long activeSessionCount,
        @Schema(description = "最近收到或结算的播放事件")
        @JsonProperty("recent_events")
        List<EmbyWebhookRecentEventResponse> recentEvents
) {
}
