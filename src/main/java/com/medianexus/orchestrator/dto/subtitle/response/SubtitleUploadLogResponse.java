package com.medianexus.orchestrator.dto.subtitle.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "字幕上传批次日志")
public record SubtitleUploadLogResponse(
        @Schema(description = "日志自增 id")
        Long id,
        @Schema(description = "所属上传批次 id")
        @JsonProperty("upload_id")
        String uploadId,
        @Schema(description = "日志级别：INFO、WARN 或 ERROR")
        String level,
        @Schema(description = "处理阶段")
        String stage,
        @Schema(description = "面向前端展示的日志消息")
        String message,
        @Schema(description = "日志附加详情；没有附加信息时为 null", nullable = true)
        String detail,
        @Schema(description = "日志创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt
) {
}
