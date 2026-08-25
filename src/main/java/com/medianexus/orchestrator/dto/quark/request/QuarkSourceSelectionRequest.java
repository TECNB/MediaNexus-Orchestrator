package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A user decision for one opaque share-tree candidate. */
public record QuarkSourceSelectionRequest(
        @JsonProperty("source_candidate_id") String sourceCandidateId,
        @JsonProperty("season_number") Integer seasonNumber,
        boolean ignored,
        @JsonProperty("follow_updates") boolean followUpdates
) {
}
