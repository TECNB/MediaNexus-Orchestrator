package com.medianexus.orchestrator.dto.admin.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminMediaIdentifyRequest(
        @NotBlank String library,
        @NotBlank @Size(max = 200) String query,
        @Min(1800) @Max(3000) Integer year,
        @NotBlank @JsonProperty("candidate_id") String candidateId,
        @JsonProperty("replace_all_images") boolean replaceAllImages
) {
}
