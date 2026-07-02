package com.medianexus.orchestrator.dto.taskcenter.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "任务中心发布资源重试上下文")
public record OpenListReleaseRetryContextResponse(
        @Schema(description = "底层任务类型：MOVIE、SERIES 或 ANIME")
        @JsonProperty("task_type")
        String taskType,
        @Schema(description = "原任务 id")
        @JsonProperty("task_id")
        String taskId,
        @Schema(description = "任务中心产品类别：MOVIE、SERIES 或 ANIME")
        @JsonProperty("product_type")
        String productType,
        @Schema(description = "用于重新搜索发布资源的展示标题")
        String title,
        @Schema(description = "用于重新搜索发布资源的原始标题；原任务未记录时为 null", nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "电影年份；非电影任务或原任务未记录时为 null", nullable = true)
        Integer year,
        @Schema(description = "剧集或动漫季度；电影任务为 null", nullable = true)
        @JsonProperty("season_number")
        Integer seasonNumber,
        @Schema(description = "原任务质量偏好；原任务未记录时为 null", nullable = true)
        @JsonProperty("quality_tag")
        String qualityTag,
        @Schema(description = "原任务当前发布标题；当前尝试不是发布资源或历史任务未记录时为 null", nullable = true)
        @JsonProperty("release_title")
        String releaseTitle,
        @Schema(description = "原任务当前发布索引器；当前尝试不是发布资源或历史任务未记录时为 null", nullable = true)
        @JsonProperty("release_indexer")
        String releaseIndexer,
        @Schema(description = "原任务当前发布大小；当前尝试不是发布资源或历史任务未记录时为 null", nullable = true)
        @JsonProperty("release_size")
        Long releaseSize,
        @Schema(description = "原任务当前发布分辨率标签；没有记录时为空列表")
        @JsonProperty("resolution_tags")
        List<String> resolutionTags,
        @Schema(description = "原任务当前发布动态范围标签；没有记录时为空列表")
        @JsonProperty("dynamic_range_tags")
        List<String> dynamicRangeTags
) {
}
