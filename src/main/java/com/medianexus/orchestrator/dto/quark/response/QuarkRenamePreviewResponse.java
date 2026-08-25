package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One bounded, user-facing file rename preview row. */
public record QuarkRenamePreviewResponse(
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("target_name") String targetName,
        @JsonProperty("episode_number") Integer episodeNumber,
        String status,
        String message
) {
}
