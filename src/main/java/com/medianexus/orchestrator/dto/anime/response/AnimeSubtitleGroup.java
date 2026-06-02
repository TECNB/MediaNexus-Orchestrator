package com.medianexus.orchestrator.dto.anime.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnimeSubtitleGroup(
        String id,
        String label,
        String rss,
        @JsonProperty("bgm_url")
        String bgmUrl,
        String language,
        @JsonProperty("item_count")
        int itemCount,
        @JsonProperty("update_day")
        String updateDay
) {
}
