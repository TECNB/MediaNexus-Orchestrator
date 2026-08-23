package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkIngestPreviewResponse(
        boolean ready,
        @JsonProperty("media_type")
        String mediaType,
        @JsonProperty("save_path")
        String savePath,
        @JsonProperty("planned_task_count")
        int plannedTaskCount,
        @JsonProperty("video_count")
        int videoCount,
        @JsonProperty("subtitle_count")
        int subtitleCount,
        @JsonProperty("directory_count")
        int directoryCount,
        @JsonProperty("max_depth")
        int maxDepth,
        List<QuarkSharePreviewNodeResponse> entries,
        List<QuarkIngestPreviewTaskResponse> tasks,
        List<String> warnings,
        String message
) {

    public QuarkIngestPreviewResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
