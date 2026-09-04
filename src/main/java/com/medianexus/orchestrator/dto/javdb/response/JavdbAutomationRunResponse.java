package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "JAVDB 自动化运行记录")
public record JavdbAutomationRunResponse(
        String id,
        @JsonProperty("trigger_type") String triggerType,
        @JsonProperty("triggered_by_user_id") Long triggeredByUserId,
        @JsonProperty("execution_mode") String executionMode,
        @Schema(description = "RUNNING、SUCCEEDED、PARTIAL_SUCCESS、FAILED、INTERRUPTED 或 SKIPPED")
        String status,
        String stage,
        @JsonProperty("ranking_entries") int rankingEntries,
        @JsonProperty("unique_movies") int uniqueMovies,
        @JsonProperty("duplicate_entries_removed") int duplicateEntriesRemoved,
        @JsonProperty("already_in_emby") int alreadyInEmby,
        @JsonProperty("history_duplicates") int historyDuplicates,
        @JsonProperty("active_duplicates") int activeDuplicates,
        @JsonProperty("remaining_movies") int remainingMovies,
        @JsonProperty("submitted_count") int submittedCount,
        @JsonProperty("adult_task_count") int adultTaskCount,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("started_at") LocalDateTime startedAt,
        @JsonProperty("finished_at") LocalDateTime finishedAt,
        List<JavdbAutomationRunItemResponse> items,
        List<JavdbAutomationRunLogResponse> logs
) {
}
