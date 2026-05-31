package com.medianexus.orchestrator.dto.magnet;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnimeMagnetSearchItem(
        String id,
        @JsonProperty("bgm_id")
        String bgmId,
        @JsonProperty("bgm_url")
        String bgmUrl,
        String title,
        @JsonProperty("name_cn")
        String nameCn,
        String name,
        String cover,
        Double score,
        Integer eps,
        @JsonProperty("air_date")
        String airDate,
        Integer season,
        String platform
) {
}
