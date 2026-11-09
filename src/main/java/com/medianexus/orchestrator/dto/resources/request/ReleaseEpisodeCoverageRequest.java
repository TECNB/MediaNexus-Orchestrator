package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "发布资源的可选正片集数核验，不影响搜索和入库")
public record ReleaseEpisodeCoverageRequest(
        @JsonProperty("download_ref") @NotBlank
        @Schema(description = "待核验的 magnet；仅已搜索到的可信 Nyaa 来源可核验") String downloadRef,
        @JsonProperty("tmdb_id") @NotNull @Positive
        @Schema(description = "TMDB 剧集正整数 ID") Integer tmdbId,
        @JsonProperty("season_number") @NotNull @Positive
        @Schema(description = "目标正片季度，必须为正整数") Integer seasonNumber
) {
}
