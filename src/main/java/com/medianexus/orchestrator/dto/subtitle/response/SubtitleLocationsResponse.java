package com.medianexus.orchestrator.dto.subtitle.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "实际存在媒体目录的下载目标；不包含服务器路径")
public record SubtitleLocationsResponse(List<Item> items) {
    @Schema(description = "字幕可上传到的下载目标")
    public record Item(
            @JsonProperty("target_id")
            @Schema(description = "服务端登记的稳定目标 ID；未接管时为 legacy")
            String targetId,
            @JsonProperty("target_name")
            @Schema(description = "目标显示名")
            String targetName
    ) {}
}
