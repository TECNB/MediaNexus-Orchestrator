package com.medianexus.orchestrator.dto.taskcenter.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkTaskCenterRetryRequest(
        @JsonProperty("child_task_ids") List<String> childTaskIds
) {
    public QuarkTaskCenterRetryRequest {
        childTaskIds = childTaskIds == null ? List.of() : List.copyOf(childTaskIds);
    }
}
