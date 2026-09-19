package com.medianexus.orchestrator.dto.javdb.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JavdbPlaylistSyncItemResponse(
        String code,
        @JsonProperty("playlist_key") String playlistKey,
        @JsonProperty("playlist_name") String playlistName,
        String result
) {
}
