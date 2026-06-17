package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "剧集 magnet 离线提交响应")
public record SeriesMagnetIngestResponse(
        @Schema(description = "OpenList 最终保存目录")
        @JsonProperty("save_path")
        String savePath,
        @Schema(description = "清洗后的剧集目录名")
        @JsonProperty("series_name")
        String seriesName,
        @Schema(description = "季目录名，例如 Season 01")
        @JsonProperty("season_folder")
        String seasonFolder
) {
}
