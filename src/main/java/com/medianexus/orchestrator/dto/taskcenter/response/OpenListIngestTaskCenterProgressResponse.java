package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "任务中心 OpenList 入库任务进度数据")
public record OpenListIngestTaskCenterProgressResponse(
        @Schema(description = "已整理数量；电影、剧集和动漫任务使用")
        @JsonProperty("organized_count")
        Integer organizedCount,
        @Schema(description = "已跳过数量；电影、剧集和动漫任务使用")
        @JsonProperty("skipped_count")
        Integer skippedCount,
        @Schema(description = "已提交数量；Adult 批量任务使用")
        @JsonProperty("submitted_count")
        Integer submittedCount,
        @Schema(description = "成功数量；Adult 批量任务使用")
        @JsonProperty("succeeded_count")
        Integer succeededCount,
        @Schema(description = "失败数量；Adult 批量任务使用")
        @JsonProperty("failed_count")
        Integer failedCount,
        @Schema(description = "重复数量；Adult 批量任务使用")
        @JsonProperty("duplicate_count")
        Integer duplicateCount,
        @Schema(description = "保留数量；Adult 批量任务使用")
        @JsonProperty("kept_count")
        Integer keptCount,
        @Schema(description = "删除数量；Adult 批量任务使用")
        @JsonProperty("deleted_count")
        Integer deletedCount
) {
}
