package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminMediaPosterCandidateResponse(
        @JsonProperty("candidate_id") String candidateId,
        @JsonProperty("provider_name") String providerName,
        Integer width,
        Integer height,
        String language
) {
}
