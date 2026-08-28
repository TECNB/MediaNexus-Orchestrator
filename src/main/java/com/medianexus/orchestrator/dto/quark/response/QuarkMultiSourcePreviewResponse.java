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
        @JsonProperty("season_coverages") List<QuarkSeasonCoverageResponse> seasonCoverages,
        @JsonProperty("episode_alignments") List<QuarkEpisodeAlignmentResponse> episodeAlignments,
        @JsonProperty("planned_task_count") int plannedTaskCount,
        List<String> warnings,
        String message
) {

    public QuarkMultiSourcePreviewResponse(
            boolean ready,
            String previewId,
            String mediaType,
            String saveRoot,
            String rootSourceCandidateId,
            List<QuarkSourceTreeNodeResponse> entries,
            List<QuarkSourcePlanResponse> sources,
            List<QuarkSeasonCoverageResponse> seasonCoverages,
            List<String> warnings,
            String message
    ) {
        this(
                ready, previewId, mediaType, saveRoot, rootSourceCandidateId, entries, sources,
                seasonCoverages, List.of(), 0, warnings, message
        );
    }

    public QuarkMultiSourcePreviewResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
        sources = sources == null ? List.of() : List.copyOf(sources);
        seasonCoverages = seasonCoverages == null ? List.of() : List.copyOf(seasonCoverages);
        episodeAlignments = episodeAlignments == null ? List.of() : List.copyOf(episodeAlignments);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
