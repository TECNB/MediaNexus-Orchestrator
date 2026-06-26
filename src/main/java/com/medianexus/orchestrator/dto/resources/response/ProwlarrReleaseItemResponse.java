package com.medianexus.orchestrator.dto.resources.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Prowlarr 发布资源条目")
public record ProwlarrReleaseItemResponse(
        @Schema(description = "发布标题")
        String title,
        @Schema(description = "发布体积，单位 byte；上游未返回时为 null", nullable = true)
        Long size,
        @Schema(description = "做种数；上游未返回时为 null", nullable = true)
        Integer seeders,
        @Schema(description = "下载者数量；上游未返回时为 null", nullable = true)
        Integer leechers,
        @Schema(description = "抓取次数；上游未返回时为 null", nullable = true)
        Integer grabs,
        @Schema(description = "索引器名称")
        String indexer,
        @Schema(description = "发布时间文本；上游未返回时为 null", nullable = true)
        @JsonProperty("publish_date")
        String publishDate,
        @Schema(description = "Prowlarr 索引器 id")
        @JsonProperty("indexer_id")
        Integer indexerId,
        @Schema(description = "服务端用于解析 magnet 的不透明下载引用")
        @JsonProperty("download_ref")
        String downloadRef,
        @Schema(description = "从发布标题解析出的分辨率标签")
        @JsonProperty("resolution_tags")
        List<String> resolutionTags,
        @Schema(description = "从发布标题解析出的动态范围标签")
        @JsonProperty("dynamic_range_tags")
        List<String> dynamicRangeTags,
        @Schema(description = "命中来源；旧通用发布搜索没有搜索计划来源时为 null", nullable = true)
        @JsonProperty("match_source")
        String matchSource,
        @Schema(description = "命中查询内容；旧通用发布搜索没有搜索计划来源时为 null", nullable = true)
        @JsonProperty("match_query")
        String matchQuery
) {
}
