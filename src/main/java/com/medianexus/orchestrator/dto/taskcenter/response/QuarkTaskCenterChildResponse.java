package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record QuarkTaskCenterChildResponse(
        String id,
        @JsonProperty("task_name") String taskName,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("save_path") String savePath,
        @JsonProperty("version_label") String versionLabel,
        String status,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("subscription_enabled") boolean subscriptionEnabled,
        @JsonProperty("planned_file_count") int plannedFileCount,
        @JsonProperty("processed_file_count") int processedFileCount,
        @JsonProperty("renamed_file_count") int renamedFileCount,
        @JsonProperty("ignored_file_count") int ignoredFileCount,
        @JsonProperty("failed_file_count") int failedFileCount,
        @JsonProperty("unknown_file_count") int unknownFileCount,
        List<QuarkTaskCenterFileResponse> files,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public QuarkTaskCenterChildResponse {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
