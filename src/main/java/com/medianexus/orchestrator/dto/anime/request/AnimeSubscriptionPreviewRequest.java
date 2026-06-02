package com.medianexus.orchestrator.dto.anime.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AnimeSubscriptionPreviewRequest(
        String rss,
        @JsonProperty("bgm_url")
        @JsonAlias("bgmUrl")
        String bgmUrl,
        String subgroup
) {
}
