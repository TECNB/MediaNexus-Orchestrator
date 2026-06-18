package com.medianexus.orchestrator.dto.magnet.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "电影 magnet 导入任务详情")
public record MovieMagnetIngestTaskResponse(
        @Schema(description = "导入任务 id")
        String id,
        @Schema(description = "创建任务的用户 id；历史无归属任务可能为 null", nullable = true)
        @JsonProperty("created_by_user_id")
        Long createdByUserId,
        @Schema(description = "任务状态：PENDING、SUBMITTED、DOWNLOADING、ORGANIZING、SUCCEEDED、PARTIAL_SUCCESS、FAILED 或 INTERRUPTED")
        String status,
        @Schema(description = "前端展示阶段，例如 created、submitted、downloading、organizing、succeeded、failed")
        String stage,
        @Schema(description = "电影标题")
        String title,
        @Schema(description = "电影原始标题；未提供时为 null", nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "电影年份")
        Integer year,
        @Schema(description = "magnet btih hash，统一小写")
        @JsonProperty("magnet_hash")
        String magnetHash,
        @Schema(description = "最终保存目录，按当前 Radarr 电影目录格式渲染")
        @JsonProperty("save_path")
        String savePath,
        @Schema(description = "离线下载临时目录；当前与保存目录一致")
        @JsonProperty("temp_path")
        String tempPath,
        @Schema(description = "已整理入库的视频或字幕文件数量")
        @JsonProperty("organized_count")
        Integer organizedCount,
        @Schema(description = "无法识别、重复或被排除而跳过的文件数量")
        @JsonProperty("skipped_count")
        Integer skippedCount,
        @Schema(description = "失败时的截断错误信息；非失败状态时为 null", nullable = true)
        @JsonProperty("error_message")
        String errorMessage,
        @Schema(description = "任务创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt,
        @Schema(description = "任务更新时间")
        @JsonProperty("updated_at")
        LocalDateTime updatedAt,
        @Schema(description = "终态任务完成时间；任务未进入终态时为 null", nullable = true)
        @JsonProperty("finished_at")
        LocalDateTime finishedAt
) {
}
