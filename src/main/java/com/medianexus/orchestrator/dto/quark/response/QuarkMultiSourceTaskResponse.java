package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkMultiSourceTaskResponse(
        String id,
        String status,
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("save_root") String saveRoot,
        @JsonProperty("immediate_execution_started") boolean immediateExecutionStarted,
        @JsonProperty("planned_task_count") int plannedTaskCount,
        @JsonProperty("created_task_count") int createdTaskCount,
        List<QuarkSourceTaskResultResponse> sources,
        List<String> warnings,
        String message
) {

    public QuarkMultiSourceTaskResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
