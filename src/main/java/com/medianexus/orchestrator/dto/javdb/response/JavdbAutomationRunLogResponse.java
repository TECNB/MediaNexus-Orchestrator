package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record JavdbAutomationRunLogResponse(
        long id,
        @JsonProperty("run_id") String runId,
        String level,
        String stage,
        String message,
        String detail,
        @JsonProperty("created_at") LocalDateTime createdAt
) {
}
