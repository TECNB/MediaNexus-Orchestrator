package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuarkTaskCenterActionResponse(
        String id,
        String status,
        String message,
        @JsonProperty("detail_path") String detailPath
) {
}
