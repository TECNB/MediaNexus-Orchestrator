package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

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

        @JsonProperty("failed_magnets")
        List<AdultMagnetFailureResponse> failedMagnets,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("updated_at")
        LocalDateTime updatedAt,

        @JsonProperty("finished_at")
        LocalDateTime finishedAt
) {

    public AdultMagnetIngestTaskResponse(
            String id,
            Long createdByUserId,
            String category,
            String status,
            String stage,
            String dateFolder,
            String targetPath,
            int magnetCount,
            int submittedCount,
            int succeededCount,
            int failedCount,
            int duplicateCount,
            int keptCount,
            int deletedCount,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime finishedAt
    ) {
        this(
                id, createdByUserId, category, status, stage, dateFolder, targetPath,
                magnetCount, submittedCount, succeededCount, failedCount,
                duplicateCount, keptCount, deletedCount, errorMessage, List.of(),
                createdAt, updatedAt, finishedAt
        );
    }
}
