package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** A user correction for one opaque file in a share-tree candidate. */
public record QuarkFileSelectionRequest(
        @NotBlank @JsonProperty("file_id") String fileId,
        @Min(1) @Max(999) @JsonProperty("episode_number") Integer episodeNumber,
        boolean ignored
) {
}
