package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkSourcePlanResponse(
        @JsonProperty("source_candidate_id") String sourceCandidateId,
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("relative_path") String relativePath,
        @JsonProperty("source_kind") String sourceKind,
        @JsonProperty("detected_season") Integer detectedSeason,
        @JsonProperty("season_status") String seasonStatus,
        @JsonProperty("selected_season") Integer selectedSeason,
        boolean ignored,
        @JsonProperty("follow_updates") boolean followUpdates,
        @JsonProperty("save_path") String savePath,
        @JsonProperty("task_name")
        String taskName,
        String status,
        List<QuarkRenamePreviewResponse> files,
        List<String> errors,
        List<String> warnings
) {

    public QuarkSourcePlanResponse {
        files = files == null ? List.of() : List.copyOf(files);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
