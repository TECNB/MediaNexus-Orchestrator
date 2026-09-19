package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record JavdbPlaylistSyncRunResponse(
        String id,
        @JsonProperty("trigger_type") String triggerType,
        String status,
        @JsonProperty("desired_count") int desiredCount,
        @JsonProperty("added_count") int addedCount,
        @JsonProperty("existing_count") int existingCount,
        @JsonProperty("waiting_count") int waitingCount,
        @JsonProperty("failed_count") int failedCount,
        @JsonProperty("scheduled_time") String scheduledTime,
        @JsonProperty("started_at") LocalDateTime startedAt,
        @JsonProperty("finished_at") LocalDateTime finishedAt,
        @JsonProperty("error_message") String errorMessage,
        List<JavdbPlaylistSyncGroupResponse> groups,
        List<JavdbPlaylistSyncItemResponse> items
) {
}
