package com.medianexus.orchestrator.dto.subtitle.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "主动检查字幕文件；COMPLETE 仅代表源目录文件齐全，不表示 AS 或 Emby 已完成")
public record SubtitleUploadCheckResponse(String status, Location source, Location output) {
    @Schema(description = "指定位置检查；不可访问时返回 UNAVAILABLE 及原因")
    public record Location(String status, List<File> files, String message) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record File(String name, Long expectedSize, Long actualSize, boolean present, boolean matchesSize) { }
}
