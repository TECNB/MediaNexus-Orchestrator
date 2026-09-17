package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AdminMediaPosterSelectRequest(
        @NotBlank String library,
        @NotBlank @JsonProperty("candidate_id") String candidateId
) {
}
