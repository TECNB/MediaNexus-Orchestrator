package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QuarkSeasonCoverageResponse(
        @JsonProperty("season_number") int seasonNumber,
        @JsonProperty("video_count") int videoCount,
        @JsonProperty("recognized_episode_count") int recognizedEpisodeCount,
        @JsonProperty("expected_episode_count") Integer expectedEpisodeCount,
        @JsonProperty("aired_episode_count") Integer airedEpisodeCount,
        @JsonProperty("missing_episode_numbers") List<Integer> missingEpisodeNumbers,
        @JsonProperty("extra_episode_numbers") List<Integer> extraEpisodeNumbers,
        @JsonProperty("unknown_video_count") int unknownVideoCount,
        @JsonProperty("ignored_video_count") int ignoredVideoCount,
        @JsonProperty("unknown_air_date_numbers") List<Integer> unknownAirDateNumbers,
        @JsonProperty("coverage_status") String coverageStatus,
        String message,
        List<QuarkSeasonEpisodeResponse> episodes
) {

    public QuarkSeasonCoverageResponse(
            int seasonNumber,
            int videoCount,
            int recognizedEpisodeCount,
            Integer expectedEpisodeCount,
            Integer airedEpisodeCount,
            List<Integer> missingEpisodeNumbers,
            List<Integer> extraEpisodeNumbers,
            int unknownVideoCount,
            int ignoredVideoCount,
            List<Integer> unknownAirDateNumbers,
            String coverageStatus,
            String message
    ) {
        this(
                seasonNumber, videoCount, recognizedEpisodeCount, expectedEpisodeCount, airedEpisodeCount,
                missingEpisodeNumbers, extraEpisodeNumbers, unknownVideoCount, ignoredVideoCount,
                unknownAirDateNumbers, coverageStatus, message, List.of()
        );
    }

    public QuarkSeasonCoverageResponse {
        missingEpisodeNumbers = missingEpisodeNumbers == null ? List.of() : List.copyOf(missingEpisodeNumbers);
        extraEpisodeNumbers = extraEpisodeNumbers == null ? List.of() : List.copyOf(extraEpisodeNumbers);
        unknownAirDateNumbers = unknownAirDateNumbers == null ? List.of() : List.copyOf(unknownAirDateNumbers);
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }
}
