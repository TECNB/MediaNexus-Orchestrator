package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "电影搜索结果条目")
public record MovieSearchItem(
        String id,
        String title,
        @JsonProperty("original_title")
        String originalTitle,
        Integer year,
        String overview,
        String poster,
        @JsonProperty("tmdb_id")
        Integer tmdbId,
        @JsonProperty("imdb_id")
        String imdbId,
        String status
) {
}
