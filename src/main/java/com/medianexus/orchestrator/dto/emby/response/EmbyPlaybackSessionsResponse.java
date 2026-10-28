package com.medianexus.orchestrator.dto.emby.response;

import com.medianexus.orchestrator.integration.emby.EmbyPlaybackSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "实时播放会话快照；成功空列表与上游失败分开表达")
public record EmbyPlaybackSessionsResponse(
        @Schema(description = "会话采集时间，UTC") Instant collectedAt,
        List<EmbyPlaybackSession> sessions
) {}
