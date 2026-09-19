package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JavdbRankingAppearanceResponse(
        String period,
        int rank,
        @JsonProperty("has_magnet_badge") boolean hasMagnetBadge,
        Double rating,
        @JsonProperty("review_count") Integer reviewCount
) {
}
