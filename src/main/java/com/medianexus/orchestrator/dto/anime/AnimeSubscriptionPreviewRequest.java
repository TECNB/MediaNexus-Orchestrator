package com.medianexus.orchestrator.dto.anime;

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
