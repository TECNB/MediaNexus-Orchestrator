package com.medianexus.orchestrator.dto.magnet;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record AnimeMagnetIngestTaskLogResponse(
        Long id,
        @JsonProperty("task_id")
        String taskId,
        String level,
        String stage,
        String message,
        String detail,
        @JsonProperty("created_at")
        LocalDateTime createdAt
) {
}
