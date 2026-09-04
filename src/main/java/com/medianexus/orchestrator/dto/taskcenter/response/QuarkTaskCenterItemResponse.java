package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record QuarkTaskCenterItemResponse(
        @JsonProperty("task_type") String taskType,
        String id,
        @JsonProperty("product_type") String productType,
        @JsonProperty("created_by_user_id") Long createdByUserId,
        @JsonProperty("created_by_username") String createdByUsername,
        String title,
        String status,
        String stage,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("progress_summary") String progressSummary,
        @JsonProperty("attempt_count") int attemptCount,
        @JsonProperty("planned_unit_count") int plannedUnitCount,
        @JsonProperty("completed_unit_count") int completedUnitCount,
        @JsonProperty("total_file_count") int totalFileCount,
        @JsonProperty("processed_file_count") int processedFileCount,
        @JsonProperty("failed_file_count") int failedFileCount,
        @JsonProperty("subscription_enabled") boolean subscriptionEnabled,
        @JsonProperty("detail_path") String detailPath,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
}
