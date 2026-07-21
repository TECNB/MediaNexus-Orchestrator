package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "入库前 Emby 媒体存在性检查结果")
public record MediaLibraryPresenceResponse(
        @JsonProperty("check_available")
        boolean checkAvailable,
        boolean exists,
        @JsonProperty("tmdb_id")
        Integer tmdbId,
        @JsonProperty("matched_title")
        String matchedTitle,
        @JsonProperty("season_number")
        Integer seasonNumber
) {
}
