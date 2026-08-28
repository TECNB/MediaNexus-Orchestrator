package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A user correction for one opaque file in a share-tree candidate. */
public record QuarkFileSelectionRequest(
        @NotBlank @JsonProperty("file_id") String fileId,
        @Min(1) @Max(999) @JsonProperty("episode_number") Integer episodeNumber,
        boolean ignored,
        @Size(max = 32) @JsonProperty("assignment_type") String assignmentType,
        @Size(max = 120) @JsonProperty("edition_label") String editionLabel,
        @Size(max = 120) @JsonProperty("segment_label") String segmentLabel,
        boolean forced
) {

    /** Backwards-compatible shape used by clients before alignment metadata existed. */
    public QuarkFileSelectionRequest(String fileId, Integer episodeNumber, boolean ignored) {
        this(fileId, episodeNumber, ignored, null, null, null, false);
    }
}
