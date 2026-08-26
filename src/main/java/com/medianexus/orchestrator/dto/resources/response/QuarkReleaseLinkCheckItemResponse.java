package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuarkReleaseLinkCheckItemResponse(
        String id,
        String availability,
        @JsonProperty("availability_summary")
        String availabilitySummary
) {
}
