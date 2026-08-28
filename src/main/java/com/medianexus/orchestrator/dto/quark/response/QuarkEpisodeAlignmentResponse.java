package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** User-facing TMDB alignment row. */
public record QuarkEpisodeAlignmentResponse(
        @JsonProperty("season_number") int seasonNumber,
        @JsonProperty("episode_number") int episodeNumber,
        @JsonProperty("air_date") String airDate,
        @JsonProperty("episode_title") String episodeTitle,
        List<QuarkRenamePreviewResponse> files,
        String status,
        String message
) {

    public QuarkEpisodeAlignmentResponse {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
