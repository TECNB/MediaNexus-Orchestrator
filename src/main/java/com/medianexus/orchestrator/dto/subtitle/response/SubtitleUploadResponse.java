package com.medianexus.orchestrator.dto.subtitle.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "电影或剧集字幕上传批次")
public record SubtitleUploadResponse(
        @Schema(description = "上传批次 id")
        String id,
        @Schema(description = "创建上传批次的用户 id")
        @JsonProperty("created_by_user_id")
        Long createdByUserId,
        @Schema(description = "媒体类型：MOVIE 或 SERIES")
        @JsonProperty("media_type")
        String mediaType,
        @Schema(description = "批次状态：PROCESSING、WAITING_FOR_AS 或 FAILED")
        String status,
        @Schema(description = "当前阶段，例如 created、extracting、uploading、waiting_for_as、failed")
        String stage,
        @Schema(description = "电影或剧集标题")
        String title,
        @Schema(description = "原始标题；未提供时为 null", nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "电影年份；剧集上传时可为 null", nullable = true)
        Integer year,
        @Schema(description = "剧集季数；电影上传时为 null", nullable = true)
        @JsonProperty("season_number")
        Integer seasonNumber,
        @Schema(description = "OpenList 目标电影目录或剧集 Season 目录")
        @JsonProperty("target_path")
        String targetPath,
        @Schema(description = "电影命名基准视频，或剧集匹配视频摘要")
        @JsonProperty("selected_video_name")
        String selectedVideoName,
        @Schema(description = "用户上传的源文件名")
        @JsonProperty("source_file_name")
        String sourceFileName,
        @Schema(description = "用户上传源文件大小，单位字节")
        @JsonProperty("source_size")
        Long sourceSize,
        @Schema(description = "用户上传源文件 SHA-256")
        @JsonProperty("source_sha256")
        String sourceSha256,
        @Schema(description = "上传入目标目录的字幕文件数量")
        @JsonProperty("file_count")
        Integer fileCount,
        @Schema(description = "是否允许覆盖目标目录中同名字幕")
        @JsonProperty("overwrite_enabled")
        Boolean overwriteEnabled,
        @Schema(description = "批次中的字幕文件明细")
        List<SubtitleUploadedFileResponse> files,
        @Schema(description = "失败时的截断错误信息；非失败状态时为 null", nullable = true)
        @JsonProperty("error_message")
        String errorMessage,
        @Schema(description = "批次创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt,
        @Schema(description = "批次更新时间")
        @JsonProperty("updated_at")
        LocalDateTime updatedAt,
        @Schema(description = "批次结束时间；处理未结束时为 null", nullable = true)
        @JsonProperty("finished_at")
        LocalDateTime finishedAt
) {
}
