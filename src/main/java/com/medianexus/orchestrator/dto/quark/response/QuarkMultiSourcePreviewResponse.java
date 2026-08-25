package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkMultiSourcePreviewResponse(
        boolean ready,
        @JsonProperty("preview_id") String previewId,
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("save_root") String saveRoot,
        @JsonProperty("root_source_candidate_id") String rootSourceCandidateId,
        List<QuarkSourceTreeNodeResponse> entries,
        List<QuarkSourcePlanResponse> sources,
        List<String> warnings,
        String message
) {

    public QuarkMultiSourcePreviewResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
