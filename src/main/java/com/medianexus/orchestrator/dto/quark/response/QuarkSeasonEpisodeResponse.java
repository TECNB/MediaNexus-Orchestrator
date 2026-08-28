package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One TMDB episode slot and the source files currently aligned to it. */
public record QuarkSeasonEpisodeResponse(
        @JsonProperty("episode_number") int episodeNumber,
        @JsonProperty("air_date") String airDate,
        @JsonProperty("episode_title") String episodeTitle,
        @JsonProperty("file_ids") List<String> fileIds,
        String status,
        String message
) {

    public QuarkSeasonEpisodeResponse {
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
    }
}
