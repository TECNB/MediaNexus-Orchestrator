package com.medianexus.orchestrator.dto.magnet;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record AnimeMagnetIngestTaskResponse(
        String id,
        String status,
        String stage,
        @JsonProperty("bgm_id")
        String bgmId,
        String title,
        @JsonProperty("season_number")
        Integer seasonNumber,
        @JsonProperty("magnet_hash")
        String magnetHash,
        @JsonProperty("save_path")
        String savePath,
        @JsonProperty("temp_path")
        String tempPath,
        @JsonProperty("organized_count")
        Integer organizedCount,
        @JsonProperty("skipped_count")
        Integer skippedCount,
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
