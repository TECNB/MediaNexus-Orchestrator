package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AdminMediaDeletionRequest(
        @NotBlank String library,
        @JsonProperty("season_id") String seasonId
) {
}
