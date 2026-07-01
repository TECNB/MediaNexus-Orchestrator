package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "资源页剧集 OpenList 入库请求")
public record SeriesOpenListIngestRequest(
        @Schema(description = "用户提交搜索时的关键词快照")
        String term,
        @Schema(description = "剧集中文或展示标题")
        String title,
        @Schema(description = "剧集原始标题；没有时可为 null", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "季编号，从 1 开始")
        @JsonProperty("season_number")
        Integer seasonNumber,
        @Schema(description = "任务中心产品类别：SERIES 或 ANIME；未提供时按 SERIES 兼容", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("task_product_type")
        @Pattern(regexp = "SERIES|ANIME", message = "任务产品类别只能是 SERIES 或 ANIME")
        String taskProductType,
        @Schema(description = "期望分辨率标签，例如 2160p、1080p 或 720p")
        String quality
) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
