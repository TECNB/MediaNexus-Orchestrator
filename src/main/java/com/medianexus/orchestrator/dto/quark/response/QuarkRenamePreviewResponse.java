package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One bounded, user-facing file rename preview row. */
public record QuarkRenamePreviewResponse(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("target_name") String targetName,
        @JsonProperty("episode_number") Integer episodeNumber,
        String status,
        String message,
        @JsonProperty("detected_episode") Integer detectedEpisode,
        @JsonProperty("detected_date") String detectedDate,
        @JsonProperty("tmdb_air_date") String tmdbAirDate,
        @JsonProperty("group_id") String groupId,
        @JsonProperty("assignment_type") String assignmentType,
        @JsonProperty("edition_label") String editionLabel,
        @JsonProperty("segment_label") String segmentLabel,
        Double confidence,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        boolean forced
) {

    public QuarkRenamePreviewResponse(
            String fileId,
            String sourceName,
            String targetName,
            Integer episodeNumber,
            String status,
            String message
    ) {
        this(
                fileId, sourceName, targetName, episodeNumber, status, message,
                null, null, null, null, null, null, null, null, List.of(), false
        );
    }

    public QuarkRenamePreviewResponse {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
