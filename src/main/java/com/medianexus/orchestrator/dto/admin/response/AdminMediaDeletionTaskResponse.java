package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record AdminMediaDeletionTaskResponse(
        String id,
        String library,
        @JsonProperty("item_id") String itemId,
        @JsonProperty("season_id") String seasonId,
        @JsonProperty("season_number") Integer seasonNumber,
        String title,
        @JsonProperty("target_label") String targetLabel,
        String status,
        String stage,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("finished_at") LocalDateTime finishedAt
) {
}
