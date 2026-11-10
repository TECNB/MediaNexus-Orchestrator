package com.medianexus.orchestrator.dto.subtitle.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "字幕导入预览；仅保存本地临时文件，确认后才创建上传批次")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubtitlePreparationResponse(
        String id, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt, String targetId, String targetPath,
        List<File> files, List<String> skippedEntries, List<String> warnings
) {
    @Schema(description = "单条字幕规划；状态为 READY、CONFLICT、UNMATCHED 或 AMBIGUOUS；unknown 不代表中文")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record File(
            String id, String originalPath, String originalName, String extension,
            String language, String languageSource, Integer episodeNumber, String videoName,
            String finalName, long size, String sha256, String status, String conflictReason, List<String> warnings
    ) { }
}
