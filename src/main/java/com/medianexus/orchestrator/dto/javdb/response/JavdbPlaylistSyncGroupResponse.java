package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JavdbPlaylistSyncGroupResponse(
        String key,
        String name,
        int total,
        int added,
        int existing,
        int waiting,
        int failed,
        @JsonProperty("playlist_id") String playlistId
) {
}
