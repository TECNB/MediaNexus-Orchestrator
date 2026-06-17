package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "剧集季数响应")
public record SeriesSeasonsResponse(
        @JsonProperty("tvdb_id")
        Integer tvdbId,
        String title,
        @JsonProperty("season_count")
        int seasonCount,
        @JsonProperty("season_numbers")
        List<Integer> seasonNumbers
) {
}
