package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record QuarkIngestTaskLogResponse(
        Long id,
        @JsonProperty("task_id") String taskId,
        String level,
        String stage,
        String message,
        String detail,
        @JsonProperty("created_at") LocalDateTime createdAt
) {
}
