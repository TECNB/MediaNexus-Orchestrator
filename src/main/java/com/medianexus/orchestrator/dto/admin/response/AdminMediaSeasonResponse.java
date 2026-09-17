package com.medianexus.orchestrator.dto.admin.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminMediaSeasonResponse(
        @JsonProperty("season_id") String seasonId,
        String name,
        @JsonProperty("season_number") Integer seasonNumber,
        @JsonProperty("episode_count") int episodeCount,
        @JsonProperty("date_created") String dateCreated
) {
}
