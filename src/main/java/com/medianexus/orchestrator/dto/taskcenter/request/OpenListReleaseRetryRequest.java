package com.medianexus.orchestrator.dto.taskcenter.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "任务中心发布资源重试请求")
public record OpenListReleaseRetryRequest(
        @Schema(description = "用户选中的发布标题")
        @JsonProperty("release_title")
        @NotBlank(message = "发布标题不能为空")
        String releaseTitle,
        @Schema(description = "发布来源索引器名称；前端候选未返回时为 null", nullable = true)
        String indexer,
        @Schema(description = "发布资源大小；上游未返回时为 null", nullable = true)
        Long size,
        @Schema(description = "发布来源索引器 ID")
        @JsonProperty("indexer_id")
        @NotNull(message = "发布索引器 ID 不能为空")
        Integer indexerId,
        @Schema(description = "发布下载引用，用于后端重新解析 magnet")
        @JsonProperty("download_ref")
        @NotBlank(message = "发布下载引用不能为空")
        String downloadRef,
        @Schema(description = "发布标题解析出的分辨率标签；没有命中时为空列表或 null", nullable = true)
        @JsonProperty("resolution_tags")
        List<String> resolutionTags,
        @Schema(description = "发布标题解析出的动态范围标签；没有命中时为空列表或 null", nullable = true)
        @JsonProperty("dynamic_range_tags")
        List<String> dynamicRangeTags
) {
}
