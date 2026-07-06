package com.medianexus.orchestrator.dto.autosymlink.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "AutoSymlink 手动刷新结果")
public record AutoSymlinkRefreshResponse(
        String target,
        String status,
        String message,
        String detail,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
