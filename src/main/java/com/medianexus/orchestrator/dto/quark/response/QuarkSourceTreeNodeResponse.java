package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkSourceTreeNodeResponse(
        String name,
        boolean directory,
        long size,
        @JsonProperty("source_candidate_id") String sourceCandidateId,
        @JsonProperty("source_kind") String sourceKind,
        @JsonProperty("relative_path") String relativePath,
        @JsonProperty("detected_season") Integer detectedSeason,
        @JsonProperty("season_status") String seasonStatus,
        List<QuarkSourceTreeNodeResponse> children
) {

    public QuarkSourceTreeNodeResponse {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
