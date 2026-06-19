package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "资源页电影 OpenList 入库请求")
public record MovieOpenListIngestRequest(
        @Schema(description = "用户提交搜索时的关键词快照")
        String term,
        @Schema(description = "电影中文或展示标题")
        String title,
        @Schema(description = "电影原始标题；没有时可为 null", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "电影年份")
        Integer year,
        @Schema(description = "期望分辨率标签，例如 2160p、1080p 或 720p")
        String quality
) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
