package com.medianexus.orchestrator.dto.anime.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnimeSearchItem(
        String id,
        String title,
        String cover,
        @JsonProperty("source_url")
        String sourceUrl,
        Double score,
        Boolean exists,
        @JsonProperty("week_label")
        String weekLabel
) {
}
