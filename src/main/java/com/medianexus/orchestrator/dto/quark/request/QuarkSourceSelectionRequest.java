package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.util.List;

/** A user decision for one opaque share-tree candidate. */
public record QuarkSourceSelectionRequest(
        @JsonProperty("source_candidate_id") String sourceCandidateId,
        @JsonProperty("season_number") Integer seasonNumber,
        boolean ignored,
        @JsonProperty("follow_updates") boolean followUpdates,
        List<@Valid QuarkFileSelectionRequest> files
) {

    public QuarkSourceSelectionRequest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public QuarkSourceSelectionRequest(
            String sourceCandidateId,
            Integer seasonNumber,
            boolean ignored,
            boolean followUpdates
    ) {
        this(sourceCandidateId, seasonNumber, ignored, followUpdates, List.of());
    }
}
