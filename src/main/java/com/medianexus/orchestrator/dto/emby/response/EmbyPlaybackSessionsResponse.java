package com.medianexus.orchestrator.dto.emby.response;

import com.medianexus.orchestrator.integration.emby.EmbyPlaybackSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "实时播放会话快照；成功空列表与上游失败分开表达")
public record EmbyPlaybackSessionsResponse(
        @Schema(description = "最近已提交采样的观察时间，UTC（会话读取完成后，含可选元数据读取延迟）；尚无成功快照时为 null", nullable = true) Instant collectedAt,
        List<EmbyPlaybackSession> sessions,
        @Schema(description = "最近采集失败且保留旧快照时为 true；不表示客户端是否在线") boolean stale,
        @Schema(description = "最近一次采集尝试的开始时间，UTC；尚未尝试时为 null", nullable = true) Instant lastAttemptAt,
        @Schema(description = "采集状态：fresh 为成功，stale 为失败后保留旧快照，unavailable 为尚无成功快照") String collectionStatus,
        @Schema(description = "安全的采集状态提示；采集成功时为 null", nullable = true) String collectionMessage
) {}
