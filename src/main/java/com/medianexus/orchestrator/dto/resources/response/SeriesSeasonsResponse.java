package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "剧集季数响应")
public record SeriesSeasonsResponse(
        @Schema(description = "TMDB ID")
        @JsonProperty("tmdb_id")
        Integer tmdbId,
        String title,
        @JsonProperty("season_count")
        int seasonCount,
        @JsonProperty("season_numbers")
        List<Integer> seasonNumbers
) {
}
