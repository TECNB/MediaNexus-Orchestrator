package com.medianexus.orchestrator.dto.subtitle.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "字幕上传批次中的单个字幕文件")
public record SubtitleUploadedFileResponse(
        @Schema(description = "上传源中的原始路径；直传字幕时为原始文件名")
        @JsonProperty("original_path")
        String originalPath,
        @Schema(description = "上传源中的原始文件名")
        @JsonProperty("original_name")
        String originalName,
        @Schema(description = "写入 OpenList 目标目录后的文件名")
        @JsonProperty("final_name")
        String finalName,
        @Schema(description = "字幕文件大小，单位字节")
        Long size,
        @Schema(description = "字幕文件 SHA-256")
        String sha256
) {
}
