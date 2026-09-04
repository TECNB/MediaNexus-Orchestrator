package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record QuarkTaskCenterAttemptResponse(
        String id,
        @JsonProperty("attempt_no") int attemptNo,
        @JsonProperty("trigger_type") String triggerType,
        String status,
        String message,
        @JsonProperty("started_at") LocalDateTime startedAt,
        @JsonProperty("ended_at") LocalDateTime endedAt,
        @JsonProperty("created_by_user_id") Long createdByUserId
) {
}
