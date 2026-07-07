package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "任务中心 OpenList 入库任务详情")
public record OpenListIngestTaskCenterDetailResponse(
        @Schema(description = "底层任务类型：MOVIE、SERIES、ANIME 或 ADULT")
        @JsonProperty("task_type")
        String taskType,
        @Schema(description = "底层任务 id")
        String id,
        @Schema(description = "任务中心产品类别：MOVIE、SERIES、ANIME 或 ADULT")
        @JsonProperty("product_type")
        String productType,
        @Schema(description = "创建任务的用户 id；历史无归属任务可能为 null", nullable = true)
        @JsonProperty("created_by_user_id")
        Long createdByUserId,
        @Schema(description = "创建任务的用户名；历史无归属任务可能为 null", nullable = true)
        @JsonProperty("created_by_username")
        String createdByUsername,
        @Schema(description = "任务标题")
        String title,
        @Schema(description = "任务状态")
        String status,
        @Schema(description = "任务阶段")
        String stage,
        @Schema(description = "任务来源：MANUAL_MAGNET 或 PROWLARR_RELEASE")
        @JsonProperty("source_type")
        String sourceType,
        @Schema(description = "发布资源标题；手动 magnet 和 Adult 任务为 null", nullable = true)
        @JsonProperty("release_title")
        String releaseTitle,
        @Schema(description = "发布索引器；手动 magnet 和 Adult 任务为 null", nullable = true)
        @JsonProperty("release_indexer")
        String releaseIndexer,
        @Schema(description = "发布资源体积；手动 magnet 和 Adult 任务为 null", nullable = true)
        @JsonProperty("release_size")
        Long releaseSize,
        @Schema(description = "分辨率标签")
        @JsonProperty("resolution_tags")
        List<String> resolutionTags,
        @Schema(description = "主质量标签；没有时为 null", nullable = true)
        @JsonProperty("quality_tag")
        String qualityTag,
        @Schema(description = "动态范围标签")
        @JsonProperty("dynamic_range_tags")
        List<String> dynamicRangeTags,
        @Schema(description = "任务进度摘要")
        @JsonProperty("progress_summary")
        String progressSummary,
        @Schema(description = "任务进度数据")
        OpenListIngestTaskCenterProgressResponse progress,
        @Schema(description = "错误消息；没有时为 null", nullable = true)
        @JsonProperty("error_message")
        String errorMessage,
        @Schema(description = "最后一条 WARN 或 ERROR 日志；没有时为 null", nullable = true)
        @JsonProperty("last_warning_or_error_log")
        OpenListIngestTaskCenterLogResponse lastWarningOrErrorLog,
        @Schema(description = "首屏日志窗口，按日志 id 升序排列")
        List<OpenListIngestTaskCenterLogResponse> logs,
        @Schema(description = "当前日志窗口之前是否还有更早日志")
        @JsonProperty("logs_has_older")
        Boolean logsHasOlder,
        @Schema(description = "当前日志窗口之后是否还有更新日志")
        @JsonProperty("logs_has_newer")
        Boolean logsHasNewer,
        @Schema(description = "任务是否仍处于进行中状态")
        @JsonProperty("is_active")
        Boolean active,
        @Schema(description = "等待中说明；非 PENDING 状态为 null", nullable = true)
        @JsonProperty("pending_explanation")
        String pendingExplanation,
        @Schema(description = "Adult 原批次完整下载链接；历史任务不可回填或非 Adult 任务时为 null", nullable = true)
        @JsonProperty("batch_download_links")
        List<String> batchDownloadLinks,
        @Schema(description = "不可变任务尝试链")
        @JsonProperty("attempt_chain")
        OpenListIngestTaskCenterAttemptChainResponse attemptChain,
        @Schema(description = "任务创建时间")
        @JsonProperty("created_at")
        LocalDateTime createdAt,
        @Schema(description = "任务更新时间")
        @JsonProperty("updated_at")
        LocalDateTime updatedAt,
        @Schema(description = "任务完成时间；未完成时为 null", nullable = true)
        @JsonProperty("finished_at")
        LocalDateTime finishedAt
) {
}
