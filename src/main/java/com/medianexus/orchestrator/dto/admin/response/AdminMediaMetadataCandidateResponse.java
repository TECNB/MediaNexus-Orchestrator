package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record AdminMediaMetadataCandidateResponse(
        @JsonProperty("candidate_id") String candidateId,
        String name,
        @JsonProperty("original_title") String originalTitle,
        @JsonProperty("production_year") Integer productionYear,
        @JsonProperty("provider_ids") Map<String, String> providerIds,
        @JsonProperty("search_provider_name") String searchProviderName,
        String overview,
        @JsonProperty("has_image") boolean hasImage
) {
}
