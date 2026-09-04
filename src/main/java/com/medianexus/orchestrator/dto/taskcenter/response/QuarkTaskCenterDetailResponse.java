package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record QuarkTaskCenterDetailResponse(
        @JsonProperty("task_type") String taskType,
        String id,
        @JsonProperty("product_type") String productType,
        @JsonProperty("created_by_user_id") Long createdByUserId,
        @JsonProperty("created_by_username") String createdByUsername,
        String title,
        String status,
        String stage,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("share_urls") List<String> shareUrls,
        @JsonProperty("progress_summary") String progressSummary,
        QuarkTaskCenterProgressResponse progress,
        @JsonProperty("error_message") String errorMessage,
        List<QuarkTaskCenterChildResponse> children,
        List<QuarkTaskCenterAttemptResponse> attempts,
        List<OpenListIngestTaskCenterLogResponse> logs,
        @JsonProperty("logs_has_older") boolean logsHasOlder,
        @JsonProperty("logs_has_newer") boolean logsHasNewer,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("subscription_enabled") boolean subscriptionEnabled,
        @JsonProperty("finished_at") LocalDateTime finishedAt,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public QuarkTaskCenterDetailResponse {
        shareUrls = shareUrls == null ? List.of() : List.copyOf(shareUrls);
        children = children == null ? List.of() : List.copyOf(children);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        logs = logs == null ? List.of() : List.copyOf(logs);
    }
}
