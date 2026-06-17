package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "电影 magnet 离线提交响应")
public record MovieMagnetIngestResponse(
        @Schema(description = "OpenList 最终保存目录")
        @JsonProperty("save_path")
        String savePath
) {
}
