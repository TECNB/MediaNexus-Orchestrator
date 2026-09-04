package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuarkTaskCenterProgressResponse(
        @JsonProperty("planned_units") int plannedUnits,
        @JsonProperty("completed_units") int completedUnits,
        @JsonProperty("total_files") int totalFiles,
        @JsonProperty("processed_files") int processedFiles,
        @JsonProperty("renamed_files") int renamedFiles,
        @JsonProperty("ignored_files") int ignoredFiles,
        @JsonProperty("failed_files") int failedFiles,
        @JsonProperty("unknown_files") int unknownFiles
) {
}
