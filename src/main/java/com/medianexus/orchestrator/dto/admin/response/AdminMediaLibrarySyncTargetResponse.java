package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminMediaLibrarySyncTargetResponse(
        String label,
        String path,
        @JsonProperty("target_type") String targetType,
        String detail
) {
}
