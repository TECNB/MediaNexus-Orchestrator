package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Adult 批量 magnet 导入任务详情")
public record AdultMagnetIngestTaskResponse(
        String id,

        @JsonProperty("created_by_user_id")
        Long createdByUserId,

        String category,

        String status,

        String stage,

        @JsonProperty("date_folder")
        String dateFolder,

        @JsonProperty("target_path")
        String targetPath,

        @JsonProperty("magnet_count")
        int magnetCount,

        @JsonProperty("submitted_count")
        int submittedCount,

        @JsonProperty("succeeded_count")
        int succeededCount,

        @JsonProperty("failed_count")
        int failedCount,

        @JsonProperty("duplicate_count")
        int duplicateCount,

        @JsonProperty("kept_count")
        int keptCount,

        @JsonProperty("deleted_count")
        int deletedCount,

        @JsonProperty("error_message")
        String errorMessage,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("updated_at")
        LocalDateTime updatedAt,

        @JsonProperty("finished_at")
        LocalDateTime finishedAt
) {
}
