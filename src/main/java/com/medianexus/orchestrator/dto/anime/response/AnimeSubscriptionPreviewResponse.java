package com.medianexus.orchestrator.dto.anime.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AnimeSubscriptionPreviewResponse(
        String title,
        Integer season,
        String subgroup,
        @JsonProperty("preview_count")
        int previewCount,
        @JsonProperty("missing_episodes")
        List<Integer> missingEpisodes,
        @JsonProperty("missing_summary")
        String missingSummary,
        @JsonProperty("has_missing_episodes")
        boolean hasMissingEpisodes
) {
}
