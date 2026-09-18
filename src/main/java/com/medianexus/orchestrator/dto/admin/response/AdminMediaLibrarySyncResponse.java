package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AdminMediaLibrarySyncResponse(
        @JsonProperty("checked_directories") int checkedDirectories,
        @JsonProperty("checked_files") int checkedFiles,
        @JsonProperty("removed_directories") int removedDirectories,
        @JsonProperty("removed_items") int removedItems,
        @JsonProperty("skipped_items") int skippedItems,
        @JsonProperty("error_count") int errorCount,
        @JsonProperty("elapsed_ms") long elapsedMs,
        boolean deep,
        @JsonProperty("removed_media") List<String> removedMedia,
        @JsonProperty("skipped_media") List<String> skippedMedia,
        @JsonProperty("failed_media") List<String> failedMedia
) {
}
