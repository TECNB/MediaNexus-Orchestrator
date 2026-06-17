package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "剧集搜索结果条目")
public record SeriesSearchItem(
        String id,
        String title,
        @JsonProperty("original_title")
        String originalTitle,
        Integer year,
        String overview,
        String poster,
        @JsonProperty("tvdb_id")
        Integer tvdbId,
        @JsonProperty("imdb_id")
        String imdbId,
        @JsonProperty("tmdb_id")
        Integer tmdbId,
        String status,
        String network,
        @JsonProperty("series_type")
        String seriesType
) {
}
