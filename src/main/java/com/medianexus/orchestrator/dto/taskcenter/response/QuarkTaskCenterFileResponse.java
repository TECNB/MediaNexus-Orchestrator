package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record QuarkTaskCenterFileResponse(
        Long id,
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("target_name") String targetName,
        String status,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
}
