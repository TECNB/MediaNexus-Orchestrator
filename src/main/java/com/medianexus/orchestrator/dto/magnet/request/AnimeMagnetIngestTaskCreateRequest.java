package com.medianexus.orchestrator.dto.magnet.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnimeMagnetIngestTaskCreateRequest(
        String magnet,
        @JsonProperty("bgm_id")
        String bgmId,
        @JsonProperty("bgm_url")
        String bgmUrl,
        String title,
        @JsonProperty("name_cn")
        String nameCn,
        String name,
        @JsonProperty("season_number")
        Integer seasonNumber,
        @JsonProperty("themoviedb_name")
        String themoviedbName
) {
}
