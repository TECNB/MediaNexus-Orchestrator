package com.medianexus.orchestrator.dto.resources.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

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
        String quality,
        @Schema(description = "TMDB 电影 id；来自资源页目录结果", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("tmdb_id")
        @Positive(message = "TMDB id 必须大于 0")
        Integer tmdbId
) {

    public MovieOpenListIngestRequest(
            String term,
            String title,
            String originalTitle,
            Integer year,
            String quality
    ) {
        this(term, title, originalTitle, year, quality, null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
